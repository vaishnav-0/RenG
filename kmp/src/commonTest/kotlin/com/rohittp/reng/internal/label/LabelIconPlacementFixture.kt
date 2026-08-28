package com.rohittp.reng.internal.label

import com.rohittp.reng.internal.firewall.SpriteAtlasEntry
import com.rohittp.reng.internal.firewall.SpriteAtlasManifest
import com.rohittp.rentile.IconTextFit
import com.rohittp.rentile.LabelIconAnchor
import com.rohittp.rentile.LabelIconRef
import com.rohittp.rentile.SymbolAlignment
import com.rohittp.rentile.SymbolOverlap

/*
 * Fixtures for icon placement.
 *
 * **Every number here is chosen so that a wrong answer is a different number, and the two ways this
 * subject hides a bug are both closed at the fixture rather than in the cases:**
 *
 *  - **A square sprite entry cannot tell a width from a height, and an entry at the atlas origin
 *    cannot tell an x from a y.** [SPRITE_ENTRY] is 18 by 7 at (11, 5) in a 64 by 32 atlas: no two
 *    of those six numbers are equal, the atlas is not square, and neither entry coordinate is zero,
 *    so a `u` divided by the atlas height, a transposed rect, or a rect read from the origin all
 *    produce different texture coordinates rather than the same ones.
 *  - **The icon's drawn size is not the entry's size.** [ICON_WIDTH] and [ICON_HEIGHT] are 27 by 15
 *    -- Rentile has already divided the entry's rect by `pixelRatio` and scaled it by `icon-size`
 *    before it reaches [LabelIconRef] -- so a quad sized from the atlas rect instead of from the
 *    ref fails rather than coincidentally agreeing.
 *  - **Anchor `center` with a zero offset is the symmetry point of the whole ordering contract**:
 *    anchor, offset, rotate and translate all commute there, because steps one and two produce
 *    `(0, 0)` and every rotation fixes the origin. [iconRef] defaults to exactly that, so a case
 *    about ordering must say all three of a corner anchor, a non-zero offset and a non-zero
 *    rotation out loud -- and [LabelIconPlacementTest] does.
 *
 * The paint colours have four distinct bytes each and neither is a grey, so a channel read in the
 * wrong order lands somewhere else.
 */

/** Non-square, so an atlas height standing in for its width produces a different `u`. */
internal const val SPRITE_ATLAS_WIDTH: Int = 64

internal const val SPRITE_ATLAS_HEIGHT: Int = 32

internal const val SPRITE_NAME: String = "poi-restaurant-11"

/** The name of an **SDF** sprite, which is the only kind `icon-color` may recolour. */
internal const val SDF_SPRITE_NAME: String = "shield-motorway-2"

/** Non-square, away from the origin, and sharing no number with the atlas or with the other entry. */
internal val SPRITE_ENTRY: SpriteAtlasEntry = SpriteAtlasEntry(
    x = 11,
    y = 5,
    width = 18,
    height = 7,
    pixelRatio = 2.0,
    sdf = false,
)

/** A second entry, elsewhere in the same atlas, flagged as a signed-distance-field image. */
internal val SDF_SPRITE_ENTRY: SpriteAtlasEntry = SpriteAtlasEntry(
    x = 33,
    y = 19,
    width = 26,
    height = 9,
    pixelRatio = 1.0,
    sdf = true,
)

internal val SPRITE_MANIFEST: SpriteAtlasManifest = SpriteAtlasManifest(
    atlasWidth = SPRITE_ATLAS_WIDTH,
    atlasHeight = SPRITE_ATLAS_HEIGHT,
    entries = mapOf(SPRITE_NAME to SPRITE_ENTRY, SDF_SPRITE_NAME to SDF_SPRITE_ENTRY),
)

/** Wider than it is tall, and unequal to any atlas number, so a transposed extent is visible. */
internal const val ICON_WIDTH: Double = 27.0

internal const val ICON_HEIGHT: Double = 15.0

/**
 * One icon reference, with every field a case might turn on exposed and the rest at the style
 * specification's own default.
 *
 * The defaults are deliberately the *specification's* rather than the discriminating ones: a case
 * that wants a corner anchor, an offset or a rotation has to say so, which is what keeps the
 * symmetry point out of a case that did not mean to sit on it.
 */
@Suppress("LongParameterList")
internal fun iconRef(
    imageName: String = SPRITE_NAME,
    width: Double = ICON_WIDTH,
    height: Double = ICON_HEIGHT,
    anchor: LabelIconAnchor = LabelIconAnchor.CENTER,
    offsetX: Double = 0.0,
    offsetY: Double = 0.0,
    translateX: Double = 0.0,
    translateY: Double = 0.0,
    translateAlignment: SymbolAlignment = SymbolAlignment.VIEWPORT,
    color: Int = 0x99213243.toInt(),
    opacity: Double = 1.0,
    haloColor: Int = 0xC8546576.toInt(),
    haloWidth: Double = 0.0,
    haloBlur: Double = 0.0,
    rotationDegrees: Double = 0.0,
    padding: Double = 0.0,
    optional: Boolean = false,
    overlap: SymbolOverlap = SymbolOverlap.NEVER,
    ignorePlacement: Boolean = false,
    rotationAlignment: SymbolAlignment = SymbolAlignment.VIEWPORT,
    pitchAlignment: SymbolAlignment = SymbolAlignment.AUTO,
    keepUpright: Boolean = false,
    avoidEdges: Boolean = false,
): LabelIconRef = LabelIconRef(
    imageName = imageName,
    width = width,
    height = height,
    anchor = anchor,
    offsetX = offsetX,
    offsetY = offsetY,
    translateX = translateX,
    translateY = translateY,
    translateAlignment = translateAlignment,
    color = color,
    opacity = opacity,
    haloColor = haloColor,
    haloWidth = haloWidth,
    haloBlur = haloBlur,
    rotationDegrees = rotationDegrees,
    padding = padding,
    optional = optional,
    overlap = overlap,
    ignorePlacement = ignorePlacement,
    rotationAlignment = rotationAlignment,
    pitchAlignment = pitchAlignment,
    keepUpright = keepUpright,
    avoidEdges = avoidEdges,
    // `icon-text-fit` is out of scope by owner decision, so every fixture is unfitted and no case
    // asserts anything about these two. They are `NONE` and a zero padding rather than absent
    // because `LabelIconRef` has no default for them.
    textFit = IconTextFit.NONE,
    textFitPadding = listOf(0.0, 0.0, 0.0, 0.0),
)

/**
 * How far above the symbol anchor the coupling cases put their icon, in output pixels.
 *
 * **It exists to pull the icon's box out of the text's**, which is what makes a blocker able to hit
 * one half and miss the other. At the default anchor and offset the icon's 27-by-15 box sits wholly
 * *inside* the shared 100-by-20 text box, so no rectangle can overlap the icon alone and the
 * `optional` cases would all reduce to "the text failed", which is the one arrangement that proves
 * nothing about either flag.
 */
internal const val COUPLING_ICON_OFFSET_Y: Double = -60.0

/**
 * The icon each coupling case carries: lifted clear of the text box and nothing else.
 */
internal fun couplingIcon(
    optional: Boolean = false,
    overlap: SymbolOverlap = SymbolOverlap.NEVER,
    ignorePlacement: Boolean = false,
): LabelIconRef = iconRef(
    offsetY = COUPLING_ICON_OFFSET_Y,
    optional = optional,
    overlap = overlap,
    ignorePlacement = ignorePlacement,
)

/**
 * The recovered frame of one icon quad: where its centre is and which screen direction its own +x
 * maps to.
 *
 * Recovered from the four corners rather than read out of the implementation, so a case can ask
 * whether the icon is *where the contract puts it* without restating how it got there. The centre is
 * the mean of two opposite corners, which is exact for any parallelogram; the axis is the top edge
 * divided by the icon's own width, which is a unit vector exactly when the quad is a rigid rotation
 * of a [width] by [height] rectangle.
 */
internal fun recoverIconFrame(quad: ResolvedIconQuad, width: Double = ICON_WIDTH): RecoveredIconFrame {
    val corners = quad.cornersXy
    return RecoveredIconFrame(
        centreX = (corners[0].toDouble() + corners[4].toDouble()) / 2.0,
        centreY = (corners[1].toDouble() + corners[5].toDouble()) / 2.0,
        axisX = (corners[2].toDouble() - corners[0].toDouble()) / width,
        axisY = (corners[3].toDouble() - corners[1].toDouble()) / width,
    )
}

internal class RecoveredIconFrame(
    val centreX: Double,
    val centreY: Double,
    val axisX: Double,
    val axisY: Double,
)
