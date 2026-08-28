package com.rohittp.reng.internal.label

import com.rohittp.reng.internal.firewall.SpriteAtlasEntry
import com.rohittp.reng.internal.firewall.SpriteAtlasManifest
import com.rohittp.rentile.LabelCandidate
import com.rohittp.rentile.LabelIconAnchor
import com.rohittp.rentile.LabelIconRef
import com.rohittp.rentile.LabelPlacement
import com.rohittp.rentile.SymbolAlignment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Icons: the sprite a symbol layer pairs with its text, placed in the same output-pixel screen space
 * the glyphs are, and colliding in the same index.
 *
 * **An icon is not a parallel family, and that is the whole reason this lives beside placement
 * rather than in a file of its own path.** 681 corpus layers carry an icon and **611 of them are the
 * same layer as their text**, so a symbol is one thing that happens to have two pieces of ink. The
 * two pieces are placed independently, collide independently, and are then *coupled* by
 * `icon-optional` and `text-optional` -- see [coupleIconAndText], which is the only part of this
 * subject a reader is likely to get wrong.
 *
 * ## The ordering contract
 *
 * `LabelIconRef`'s own KDoc calls the ordering prescriptive, and Rentile's icon pass composes it in
 * exactly one place ([com.rohittp.rentile] `spriteAnchoring` plus `DefaultBasemapRasterizer`'s icon
 * loop) so that a consumer's marker and Rentile's own cannot drift. It is:
 *
 *  1. **anchor** -- `icon-anchor` names which point of the icon's box is attached to the symbol
 *     anchor, so the displacement from the symbol anchor to the icon's *centre* is derived from the
 *     box's own dimensions ([anchorShiftX]/[anchorShiftY]);
 *  2. **offset** -- `icon-offset`, already scaled by `icon-size` on the engine's side, added to that
 *     displacement while both are still icon-local;
 *  3. **rotate** -- the sum of the two turned by the icon's final rotation, because both are
 *     icon-local and therefore rotate with the image;
 *  4. **translate** -- `icon-translate` added afterwards in the frame `icon-translate-anchor`
 *     selects, and never rotated by the icon's own rotation.
 *
 * **The four steps commute at exactly one point, and that point is the style specification's
 * default**: anchor `center` with a zero offset makes steps 1 and 2 produce `(0, 0)`, which every
 * rotation fixes and every ordering therefore agrees on. A fixture built there proves nothing at
 * all, which is why [LabelIconPlacementTest] carries a case with a corner anchor, a non-zero offset
 * and a non-zero rotation together.
 *
 * ## What is deliberately not here
 *
 * **`icon-text-fit` is out of scope by owner decision** -- 110 layers across 13 styles. Step 1 above
 * is supposed to derive its displacement from the icon's *fitted* box, which is why Rentile carries
 * the anchor rather than a precomputed shift; with fit unimplemented the fitted box is the unfitted
 * one, so those layers draw their icon at its natural size. That is a recorded visible gap -- a
 * plate that is the wrong size rather than a symbol that is missing -- and it is not a licence to
 * drop the icon instead. [LabelIconRef.textFit] and [LabelIconRef.textFitPadding] are read by
 * nothing here.
 *
 * **`icon-pitch-alignment` is resolved and then not honoured.** A `map`-pitched icon lies flat on
 * the map plane and should reach the screen as a projected parallelogram; RenG draws it
 * screen-facing at its map-anchored position, which is precisely the gap the text path already has
 * for `text-pitch-alignment` (nothing in [placeLabels] or [layOutLineLabels] reads it either).
 * Honouring it means projecting four map-plane corners rather than rotating four screen-space ones,
 * and it is recorded rather than silently approximated.
 *
 * **The paint reaches no shader yet.** `icon-color` and `icon-halo-*` apply to an **SDF** sprite
 * only -- Rentile tints under `BlendMode.SRC_IN` when `entry.sdf` and passes `null` otherwise -- so
 * [SpriteAtlasEntry.sdf] is carried onto [ResolvedIconPaint.tintable] rather than left for a later
 * reader to rediscover. What no code in RenG has yet is a pipeline that samples a sprite atlas: the
 * label program thresholds its texture's alpha as a signed distance field, which is the wrong
 * arithmetic for an ordinary sprite, and the sprite atlas's *pixels* are not retained anywhere a
 * draw could reach. That is why [ResolvedIconQuad] is a sibling of
 * [com.rohittp.reng.internal.gl.ResolvedGlyphQuad] rather than the same type.
 */
internal class ResolvedIconPaint(
    /** `icon-color` as straight RGBA in `[0, 1]`, unpacked from the engine's `0xAARRGGBB`. */
    val colour: FloatArray,
    /** `icon-halo-color`, same encoding. */
    val haloColour: FloatArray,
    /** `icon-opacity`, carried separately so a fade can multiply into the same field. */
    val opacity: Float,
    /** `icon-halo-width` in **screen pixels**, not in the field units a glyph's halo is measured in. */
    val haloWidthPixels: Float,
    /** `icon-halo-blur` in screen pixels. */
    val haloBlurPixels: Float,
    /**
     * Whether this sprite is a signed-distance-field image, from the manifest entry's own `sdf`
     * member. **[colour], [haloColour], [haloWidthPixels] and [haloBlurPixels] mean nothing when this
     * is `false`**: Rentile draws a non-SDF sprite with its own pixels and no colour filter at all,
     * so tinting one would repaint artwork the style never asked to recolour.
     */
    val tintable: Boolean,
) {
    init {
        require(colour.size == RGBA_COMPONENTS) { "colour must be four components" }
        require(haloColour.size == RGBA_COMPONENTS) { "haloColour must be four components" }
    }
}

/**
 * One icon's quad, already placed: four screen corners in `CONTEXT.md`'s continuous output-pixel
 * space (origin top-left, positive y downward) in the corner order top-left, top-right,
 * bottom-right, bottom-left, and the same four corners in normalised sprite-atlas coordinates.
 *
 * **A sibling of [com.rohittp.reng.internal.gl.ResolvedGlyphQuad] rather than the same type, and the
 * reason is arithmetic rather than taste.** The two carry identically shaped geometry -- and if
 * geometry were all a quad carried, reuse would be right. It is not: a `ResolvedGlyphQuad` carries a
 * [com.rohittp.reng.internal.gl.ResolvedLabelPaint], every field of which is measured in the glyph
 * atlas's signed-distance field. `scale` is `text-size / 24`; `haloWidthPixels` is converted through
 * that scale into an iso-value by `labelHaloEdgeDistance`; and the fragment shader thresholds the
 * sampled alpha at `0.75` because that is where the `glyphs.pbf` generator puts a glyph outline. A
 * sprite has no field: its alpha is coverage, its RGB is artwork, and `icon-halo-width` is a number
 * of screen pixels rather than a distance into a field that does not exist.
 *
 * So an icon quad appended to a [com.rohittp.reng.internal.gl.LabelBatch] would not be slightly
 * wrong -- it would be hard-thresholded at an iso-value that means nothing, tinted by a colour the
 * style may never have intended to apply, and drawn with its artwork discarded. Two types make that
 * a compile error; one type makes it a picture nobody reviews. The geometry duplication is eight
 * floats and a corner-order comment; the alternative is a silent wrong answer.
 */
internal class ResolvedIconQuad(
    val cornersXy: FloatArray,
    val cornersUv: FloatArray,
    val paint: ResolvedIconPaint,
) {
    init {
        require(cornersXy.size == ICON_QUAD_CORNERS * 2) { "cornersXy must be four (x, y) pairs" }
        require(cornersUv.size == ICON_QUAD_CORNERS * 2) { "cornersUv must be four (u, v) pairs" }
    }
}

/**
 * One symbol's icon, placed and ready to collide.
 *
 * [collisionBox] is the axis-aligned screen bound of the icon's *oriented* box after `icon-padding`
 * has expanded it -- and the order matters: Rentile builds `OrientedCollisionBox` with
 * `halfWidth = width / 2 + padding` and then orients it, so the padding is expanded in the icon's
 * own frame and rotated with it. Padding a rotated box's screen bound instead would grow the
 * claim by different amounts on the two axes at every angle but a right one.
 *
 * Unlike a text label's box, this one is **not** already padded by the engine.
 * `LabelCandidate.boundingBox` carries `text-padding` on all four sides because Rentile's text
 * layout put it there; `LabelIconRef` carries `padding` as a raw evaluated number and nothing has
 * applied it, which is why it is applied here and is not a double count.
 */
internal class PlacedIcon(
    val quad: ResolvedIconQuad,
    val collisionBox: LabelScreenBox,
)

/**
 * The symbol's icon placed around [anchorPixelX]/[anchorPixelY], or `null` when this symbol has no
 * drawable icon.
 *
 * **[anchorPixelX]/[anchorPixelY] is the *symbol* anchor -- the projected geographic position before
 * `text-translate` -- and passing the text's translated anchor instead is the easiest bug in this
 * file to write and the hardest to see.** `text-translate` and `icon-translate` are two independent
 * style properties evaluated on two different layers of one symbol; a text-translated symbol whose
 * icon inherited that displacement would move its icon by the sum of both.
 *
 * [frameCosine]/[frameSine] is the unit direction the **declared frame's** +x maps to on screen: the
 * map's own basis for point placement, and the projected line's tangent at this repeat for line
 * placement. It is consulted only under `icon-rotation-alignment: map`, and `auto` resolves to
 * `viewport` for point placement and `map` otherwise, which is what the style specification says and
 * what Rentile's own icon pass does.
 *
 * `null` covers every reason an icon does not exist, and all of them are one dropped icon rather
 * than a dropped label or a failed frame:
 *
 *  - the layer declares none (`candidate.icon == null`);
 *  - **no sprite manifest was retained**, which is the ordinary state of a frame whose sprite pair
 *    came out of the consumer's Store rather than off the wire (see [SpriteAtlasManifest]);
 *  - the manifest has no entry under `imageName`, which a manifest from a *different* sprite pair
 *    than the one the engine compiled against can produce;
 *  - a placement input is not a finite number, which is the firewall's half of a check Rentile also
 *    makes;
 *  - a corner leaves `Float` range, for the reason `GlyphCell.resolveQuad` refuses one: a NaN corner
 *    compares false against every bound, survives the viewport cull, collides with nothing and
 *    reaches the GPU;
 *  - `symbol-avoid-edges` is set and the icon's claim is not wholly inside [viewport].
 *
 * **A `null` here is "this symbol has no icon", and [coupleIconAndText] reads it exactly that way**:
 * text whose icon could not be resolved places on its own terms rather than being deleted with it.
 * The alternative -- treating an unresolvable icon as an icon that failed to place -- would make a
 * missing sprite entry silently erase every label in the layer.
 */
@Suppress("LongParameterList", "ReturnCount")
internal fun resolveIcon(
    sprites: SpriteAtlasManifest?,
    candidate: LabelCandidate,
    anchorPixelX: Double,
    anchorPixelY: Double,
    frameCosine: Double,
    frameSine: Double,
    viewport: LabelScreenBox,
): PlacedIcon? {
    val icon = candidate.icon ?: return null
    val manifest = sprites ?: return null
    if (manifest.atlasWidth <= 0 || manifest.atlasHeight <= 0) return null
    val entry = manifest.entries[icon.imageName] ?: return null
    if (!icon.hasFinitePlacementInputs()) return null
    if (!anchorPixelX.isFinite() || !anchorPixelY.isFinite()) return null

    val alignedToMap = icon.rotationAlignment.resolvesAsMap(candidate.placement)
    val authoredCosine = cos(icon.rotationDegrees * ICON_RADIANS_PER_DEGREE)
    val authoredSine = sin(icon.rotationDegrees * ICON_RADIANS_PER_DEGREE)
    var cosine = if (alignedToMap) {
        authoredCosine * frameCosine - authoredSine * frameSine
    } else {
        authoredCosine
    }
    var sine = if (alignedToMap) {
        authoredSine * frameCosine + authoredCosine * frameSine
    } else {
        authoredSine
    }
    // `icon-keep-upright` turns an upside-down icon the right way up by half a turn, which is
    // Rentile's `uprightRotation` -- normalise into (-180, 180], then fold anything beyond a quarter
    // turn back by 180 degrees -- expressed on the basis rather than on the angle. Folding by 180
    // degrees *is* negating both components, and "beyond a quarter turn" is exactly a negative
    // cosine, so the two agree everywhere including at the +-90 degree boundary the fold leaves
    // alone. Doing it here avoids an atan2 round trip whose only purpose would be to be turned back
    // into a cosine and a sine.
    //
    // It applies only to a map-aligned symbol that is not point-placed, because those are the only
    // ones whose rotation comes from the geometry rather than from the author: an icon the style
    // asked to be rotated 180 degrees on a point is not upside down by accident.
    if (icon.keepUpright && alignedToMap && candidate.placement != LabelPlacement.POINT && cosine < 0.0) {
        cosine = -cosine
        sine = -sine
    }

    // Step 1 and step 2 of the ordering contract, both still icon-local, so step 3 turns their sum.
    val localShiftX = icon.anchor.anchorShiftX(icon.width) + icon.offsetX
    val localShiftY = icon.anchor.anchorShiftY(icon.height) + icon.offsetY

    // Step 4. `icon-translate-anchor` defaults to `map`, and Rentile refuses `auto` for it, so the
    // `AUTO` arm is unreachable rather than merely unlikely -- the same reading `layOutPointLabel`
    // takes for `text-translate-anchor`. The frame it rotates into is the *map's*, never the icon's:
    // an icon rotated 90 degrees by `icon-rotate` does not turn its own `icon-translate` with it.
    val translatesWithMap = icon.translateAlignment != SymbolAlignment.VIEWPORT
    val translateX = if (translatesWithMap) {
        rotatedX(icon.translateX, icon.translateY, frameCosine, frameSine)
    } else {
        icon.translateX
    }
    val translateY = if (translatesWithMap) {
        rotatedY(icon.translateX, icon.translateY, frameCosine, frameSine)
    } else {
        icon.translateY
    }

    val centreX = anchorPixelX + rotatedX(localShiftX, localShiftY, cosine, sine) + translateX
    val centreY = anchorPixelY + rotatedY(localShiftX, localShiftY, cosine, sine) + translateY

    val halfWidth = icon.width / 2.0
    val halfHeight = icon.height / 2.0
    val cornersXy = floatArrayOf(
        (centreX + rotatedX(-halfWidth, -halfHeight, cosine, sine)).toFloat(),
        (centreY + rotatedY(-halfWidth, -halfHeight, cosine, sine)).toFloat(),
        (centreX + rotatedX(halfWidth, -halfHeight, cosine, sine)).toFloat(),
        (centreY + rotatedY(halfWidth, -halfHeight, cosine, sine)).toFloat(),
        (centreX + rotatedX(halfWidth, halfHeight, cosine, sine)).toFloat(),
        (centreY + rotatedY(halfWidth, halfHeight, cosine, sine)).toFloat(),
        (centreX + rotatedX(-halfWidth, halfHeight, cosine, sine)).toFloat(),
        (centreY + rotatedY(-halfWidth, halfHeight, cosine, sine)).toFloat(),
    )
    if (cornersXy.any { !it.isFinite() }) return null

    val collisionBox = orientedScreenBound(
        centreX = centreX,
        centreY = centreY,
        halfWidth = halfWidth + icon.padding,
        halfHeight = halfHeight + icon.padding,
        cosine = cosine,
        sine = sine,
    )
    if (!collisionBox.isFinite()) return null
    // `symbol-avoid-edges` asks that a symbol never be clipped by the edge of the thing it is drawn
    // on. Rentile applies it against the output tile; RenG's output is the viewport, so that is what
    // it is applied against here. It is honoured for the icon alone: the text half is task 9's
    // recorded gap and closing it would change which labels a text-only layer places.
    if (icon.avoidEdges && !viewport.contains(collisionBox)) return null

    return PlacedIcon(
        quad = ResolvedIconQuad(
            cornersXy = cornersXy,
            cornersUv = entry.normalisedCorners(manifest),
            paint = icon.resolvePaint(entry),
        ),
        collisionBox = collisionBox,
    )
}

/**
 * Which of a symbol's two halves survive, given whether each of them could be placed on its own.
 *
 * **This is the reason 611 of the corpus's 681 icon layers being the same layer as their text
 * matters.** Two independent boxes that each win or lose their own collision is not what a symbol
 * is: a road shield with no name is not a road shield, and a name floating where its shield was
 * dropped is worse than no label. `icon-optional` and `text-optional` are how the style says which
 * half may survive alone, and the rule below is MapLibre's, stated in the style specification's own
 * words on each property:
 *
 *  - **`icon-optional`** -- "text will display without their corresponding icons when the icon
 *    collides with other symbols and the text does not". So it licenses the *text* to stand alone,
 *    and its absence makes the text depend on the icon.
 *  - **`text-optional`** -- "icons will display without their corresponding text when the text
 *    collides with other symbols and the icon does not". So it licenses the *icon* to stand alone,
 *    and its absence makes the icon depend on the text.
 *
 * Both default to `false`, which is the coupled case: a symbol with both halves places both or
 * neither.
 *
 * **Each flag is discriminated by the *other* half failing, and that is the trap.** Setting
 * `icon-optional` and then testing a symbol whose text fails proves nothing -- the icon is dropped
 * either way, because with the flag the icon needs the text and without it both halves are
 * required. What `icon-optional` decides is what happens when the **icon** fails: with it the text
 * still places, without it the text goes too.
 *
 * A symbol with only one half is not coupled to anything: [hasText] or [hasIcon] being false makes
 * the missing half vacuously optional, so a text-only candidate behaves exactly as it did before
 * icons existed and an icon whose sprite could not be resolved cannot delete its own text.
 */
internal fun coupleIconAndText(
    hasText: Boolean,
    hasIcon: Boolean,
    textPlaceable: Boolean,
    iconPlaceable: Boolean,
    iconOptional: Boolean,
    textOptional: Boolean,
): SymbolPlacementOutcome {
    val iconMayStandAlone = textOptional || !hasText
    val textMayStandAlone = iconOptional || !hasIcon
    val both = textPlaceable && iconPlaceable
    return when {
        !iconMayStandAlone && !textMayStandAlone -> SymbolPlacementOutcome(both, both)
        !textMayStandAlone -> SymbolPlacementOutcome(text = both, icon = iconPlaceable)
        !iconMayStandAlone -> SymbolPlacementOutcome(text = textPlaceable, icon = both)
        else -> SymbolPlacementOutcome(text = textPlaceable, icon = iconPlaceable)
    }
}

/** Which halves of one symbol are drawn and, therefore, which halves claim collision space. */
internal data class SymbolPlacementOutcome(val text: Boolean, val icon: Boolean)

/**
 * `icon-rotation-alignment` resolved against the symbol's placement.
 *
 * `auto` is `viewport` for a point-placed symbol and `map` for a line-placed one, which is the style
 * specification's own rule and the only reading under which an unrotated map leaves point icons
 * upright while road shields follow their road.
 */
private fun SymbolAlignment.resolvesAsMap(placement: LabelPlacement): Boolean = when (this) {
    SymbolAlignment.MAP -> true
    SymbolAlignment.VIEWPORT -> false
    SymbolAlignment.AUTO -> placement != LabelPlacement.POINT
}

/**
 * The x displacement from the symbol anchor to the icon's centre, given which point of the icon's
 * box `icon-anchor` attaches to that symbol anchor.
 *
 * `left` means the anchor sits at the box's left edge, so the centre is half a width to its
 * **right** -- the sign is the opposite of the one the name suggests, and it is the sign Rentile's
 * own `IconAnchor.shift` uses.
 */
private fun LabelIconAnchor.anchorShiftX(width: Double): Double = when (this) {
    LabelIconAnchor.CENTER, LabelIconAnchor.TOP, LabelIconAnchor.BOTTOM -> 0.0
    LabelIconAnchor.LEFT, LabelIconAnchor.TOP_LEFT, LabelIconAnchor.BOTTOM_LEFT -> width / 2.0
    LabelIconAnchor.RIGHT, LabelIconAnchor.TOP_RIGHT, LabelIconAnchor.BOTTOM_RIGHT -> -width / 2.0
}

/**
 * The y displacement, in `CONTEXT.md`'s y-**down** screen space: `top` puts the anchor at the box's
 * top edge, so the centre is half a height further down, which is a **positive** y.
 */
private fun LabelIconAnchor.anchorShiftY(height: Double): Double = when (this) {
    LabelIconAnchor.CENTER, LabelIconAnchor.LEFT, LabelIconAnchor.RIGHT -> 0.0
    LabelIconAnchor.TOP, LabelIconAnchor.TOP_LEFT, LabelIconAnchor.TOP_RIGHT -> height / 2.0
    LabelIconAnchor.BOTTOM, LabelIconAnchor.BOTTOM_LEFT, LabelIconAnchor.BOTTOM_RIGHT -> -height / 2.0
}

/**
 * The axis-aligned screen bound of a rectangle centred at [centreX]/[centreY], with the given
 * half-extents in its **own** frame, turned by [cosine]/[sine].
 *
 * Both half-extents are already padded by the caller, so the padding turns with the box rather than
 * being added to its screen bound -- see [PlacedIcon] for why those are different rectangles at
 * every angle that is not a multiple of a right angle.
 */
private fun orientedScreenBound(
    centreX: Double,
    centreY: Double,
    halfWidth: Double,
    halfHeight: Double,
    cosine: Double,
    sine: Double,
): LabelScreenBox {
    val cornerX = doubleArrayOf(-halfWidth, halfWidth, halfWidth, -halfWidth)
    val cornerY = doubleArrayOf(-halfHeight, -halfHeight, halfHeight, halfHeight)
    var left = Double.POSITIVE_INFINITY
    var top = Double.POSITIVE_INFINITY
    var right = Double.NEGATIVE_INFINITY
    var bottom = Double.NEGATIVE_INFINITY
    for (corner in cornerX.indices) {
        val x = centreX + rotatedX(cornerX[corner], cornerY[corner], cosine, sine)
        val y = centreY + rotatedY(cornerX[corner], cornerY[corner], cosine, sine)
        left = min(left, x)
        right = max(right, x)
        top = min(top, y)
        bottom = max(bottom, y)
    }
    return LabelScreenBox(left = left, top = top, right = right, bottom = bottom)
}

/**
 * This entry's four corners in normalised atlas coordinates, in [ResolvedIconQuad]'s corner order.
 *
 * The entry's rect is already known to lie wholly inside the atlas -- `spritePairJointManifest`
 * refuses the whole pair otherwise -- so no clamp is needed and none is applied.
 */
private fun SpriteAtlasEntry.normalisedCorners(manifest: SpriteAtlasManifest): FloatArray {
    val atlasWidth = manifest.atlasWidth.toFloat()
    val atlasHeight = manifest.atlasHeight.toFloat()
    val u0 = x / atlasWidth
    val v0 = y / atlasHeight
    val u1 = (x + width) / atlasWidth
    val v1 = (y + height) / atlasHeight
    return floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1)
}

/**
 * The icon's paint in the vocabulary a sprite pass would read, with [SpriteAtlasEntry.sdf] carried
 * onto it because it is what decides whether the three colour-shaped fields mean anything at all.
 */
private fun LabelIconRef.resolvePaint(entry: SpriteAtlasEntry): ResolvedIconPaint = ResolvedIconPaint(
    colour = straightRgba(color),
    haloColour = straightRgba(haloColor),
    opacity = opacity.toFloat(),
    haloWidthPixels = haloWidth.toFloat(),
    haloBlurPixels = haloBlur.toFloat(),
    tintable = entry.sdf,
)

/**
 * Every icon scalar read before a pixel is computed, checked in one place, exactly as
 * `LabelCandidate.hasFinitePlacementInputs` does for the text half.
 *
 * A non-positive width or height is refused rather than clamped: it is a zero-area quad, and the
 * collision claim it would make is a degenerate rectangle that intersects nothing.
 */
internal fun LabelIconRef.hasFinitePlacementInputs(): Boolean =
    width.isFinite() && width > 0.0 &&
        height.isFinite() && height > 0.0 &&
        offsetX.isFinite() && offsetY.isFinite() &&
        translateX.isFinite() && translateY.isFinite() &&
        rotationDegrees.isFinite() &&
        padding.isFinite() && padding >= 0.0 &&
        opacity.isFinite() && opacity >= 0.0 &&
        haloWidth.isFinite() && haloWidth >= 0.0 &&
        haloBlur.isFinite() && haloBlur >= 0.0

private const val ICON_QUAD_CORNERS: Int = 4
private const val RGBA_COMPONENTS: Int = 4
private const val ICON_RADIANS_PER_DEGREE: Double = PI / 180.0
