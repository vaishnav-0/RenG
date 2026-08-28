package com.rohittp.reng.internal.label

import com.rohittp.reng.internal.firewall.SpriteAtlasManifest
import com.rohittp.reng.internal.gl.ResolvedIconPaint
import com.rohittp.reng.internal.gl.ResolvedIconQuad
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.rentile.LabelCandidate
import com.rohittp.rentile.LabelIconAnchor
import com.rohittp.rentile.LabelIconRef
import com.rohittp.rentile.LabelPlacement
import com.rohittp.rentile.SymbolAlignment
import com.rohittp.rentile.SymbolOverlap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Icon resolution, geometry, collision, and the coupling that makes a symbol one thing rather than
 * two.
 *
 * **Three symmetries would let a wrong icon pass, and every case below is written to sit off all
 * three.**
 *
 *  1. **The ordering contract commutes at its own default.** Anchor `center` with a zero offset
 *     makes anchor-then-offset produce `(0, 0)`, which every rotation fixes, so anchor, offset,
 *     rotate and translate agree in every order. [iconGeometryFollowsAnchorThenOffsetThenRotate]
 *     therefore carries a corner anchor, a non-zero offset **and** a non-zero rotation at once, and
 *     it is the only case that claims anything about the order.
 *  2. **A square sprite, or one at the atlas origin, cannot tell an axis from its transpose.**
 *     Closed at the fixture -- see [SPRITE_ENTRY].
 *  3. **`optional` tested against a half that would have placed anyway proves nothing**, and worse,
 *     each flag is discriminated by the *other* half failing. `icon-optional` licenses the text to
 *     stand alone, so it is decided by an **icon** that fails collision; `text-optional` licenses
 *     the icon, so it is decided by **text** that fails. Both coupling cases below run their
 *     blocker-free control in the same test, so a fixture that stopped colliding would fail rather
 *     than quietly pass.
 */
class LabelIconPlacementTest {

    // ---- geometry ---------------------------------------------------------------------------

    /**
     * The whole ordering contract in one case: anchor, then offset, then rotate, then translate.
     *
     * Every one of the four steps is off its own symmetry point. The anchor is a **corner**, so its
     * shift is non-zero in both axes and the two components differ; the offset is non-zero in both
     * axes, with different magnitudes and different signs from the shift; the rotation is 35
     * degrees, which is not a multiple of a right angle, so no axis maps onto another; and the
     * translate is non-zero in both axes.
     *
     * That makes each wrong order a **different number** rather than the same one: rotating after
     * the translate moves the centre by about 6.5 pixels, applying the offset after the rotation by
     * about 0.7, and reading the anchor's shift with the opposite sign by 27. The corners are then
     * asserted as four separate expressions rather than as one offset repeated, so a transposed
     * corner order or an exchanged width and height fails too -- and the edge lengths and the axis
     * direction are checked geometrically, from the corners alone, which owes nothing to the
     * composition above.
     */
    @Test
    fun iconGeometryFollowsAnchorThenOffsetThenRotate() {
        val camera = resolvedPlacementCamera()
        val anchor = projectedAnchor(camera)
        val icon = assertNotNull(
            placedIcon(
                camera,
                iconRef(
                    anchor = LabelIconAnchor.BOTTOM_RIGHT,
                    offsetX = 9.0,
                    offsetY = -4.0,
                    rotationDegrees = ORDERING_ROTATION_DEGREES,
                    translateX = 17.0,
                    translateY = 6.0,
                ),
            ),
        )

        val cosine = cos(ORDERING_ROTATION_DEGREES * PI / 180.0)
        val sine = sin(ORDERING_ROTATION_DEGREES * PI / 180.0)
        // `bottom-right` attaches the icon's bottom-right corner to the symbol anchor, so its centre
        // is half a width to the left and half a height above -- both negative in a y-down space.
        val localX = -ICON_WIDTH / 2.0 + 9.0
        val localY = -ICON_HEIGHT / 2.0 - 4.0
        val centreX = anchor.pixelX + (localX * cosine - localY * sine) + 17.0
        val centreY = anchor.pixelY + (localX * sine + localY * cosine) + 6.0

        val half = ICON_WIDTH / 2.0
        val halfHeight = ICON_HEIGHT / 2.0
        assertCorners(
            doubleArrayOf(
                centreX + (-half * cosine + halfHeight * sine), centreY + (-half * sine - halfHeight * cosine),
                centreX + (half * cosine + halfHeight * sine), centreY + (half * sine - halfHeight * cosine),
                centreX + (half * cosine - halfHeight * sine), centreY + (half * sine + halfHeight * cosine),
                centreX + (-half * cosine - halfHeight * sine), centreY + (-half * sine + halfHeight * cosine),
            ),
            icon.quad.cornersXy,
            "icon screen corners",
        )

        // Measured from the corners alone: the top edge is the icon's width long and points along
        // the icon's own rotation, and the left edge is its height. A quad built from the atlas
        // rect's 18 by 7, or one with its extents exchanged, fails here without reference to any of
        // the arithmetic above.
        val frame = recoverIconFrame(icon.quad)
        assertClose(cosine, frame.axisX, RECOVERED_AXIS_TOLERANCE)
        assertClose(sine, frame.axisY, RECOVERED_AXIS_TOLERANCE)
        assertClose(centreX, frame.centreX, FLOAT_PIXEL_TOLERANCE)
        assertClose(centreY, frame.centreY, FLOAT_PIXEL_TOLERANCE)
        assertClose(ICON_WIDTH, edgeLength(icon.quad, from = 0, to = 1), FLOAT_PIXEL_TOLERANCE)
        assertClose(ICON_HEIGHT, edgeLength(icon.quad, from = 0, to = 3), FLOAT_PIXEL_TOLERANCE)
    }

    /**
     * `icon-anchor` names which point of the icon's box is attached to the symbol anchor, and the
     * sign is the opposite of what the name suggests: `left` puts the anchor on the box's left edge,
     * so the box's *centre* is half a width to the right.
     *
     * Four anchors are checked rather than one, chosen so that no two agree: `center` moves nothing,
     * `left` moves in x alone, `top` moves in y alone, and `bottom-right` moves in both with
     * different magnitudes and opposite signs. The icon is 27 by 15, so a shift that used the height
     * where it wanted the width lands 6 pixels away rather than on the same point.
     */
    @Test
    fun iconAnchorMovesTheCentreOffTheSymbolAnchorInBothAxes() {
        val camera = resolvedPlacementCamera()
        val anchor = projectedAnchor(camera)
        val expected = mapOf(
            LabelIconAnchor.CENTER to (0.0 to 0.0),
            LabelIconAnchor.LEFT to (ICON_WIDTH / 2.0 to 0.0),
            LabelIconAnchor.TOP to (0.0 to ICON_HEIGHT / 2.0),
            LabelIconAnchor.BOTTOM_RIGHT to (-ICON_WIDTH / 2.0 to -ICON_HEIGHT / 2.0),
        )

        for ((iconAnchor, shift) in expected) {
            val frame = recoverIconFrame(
                assertNotNull(placedIcon(camera, iconRef(anchor = iconAnchor))).quad,
            )
            assertClose(anchor.pixelX + shift.first, frame.centreX, FLOAT_PIXEL_TOLERANCE)
            assertClose(anchor.pixelY + shift.second, frame.centreY, FLOAT_PIXEL_TOLERANCE)
        }
    }

    /**
     * The four texture coordinates, straight off the manifest entry's rect.
     *
     * The atlas is 64 by 32 and the entry is 18 by 7 at (11, 5), so every one of the eight numbers
     * below is distinct: a `u` divided by the atlas height, a rect read transposed, an entry taken
     * from the origin, or a right edge computed as `x + height` all produce different coordinates.
     */
    @Test
    fun iconTextureCoordinatesComeFromTheAtlasRect() {
        val icon = assertNotNull(placedIcon(resolvedPlacementCamera(), iconRef()))
        val u0 = 11.0 / SPRITE_ATLAS_WIDTH
        val v0 = 5.0 / SPRITE_ATLAS_HEIGHT
        val u1 = 29.0 / SPRITE_ATLAS_WIDTH
        val v1 = 12.0 / SPRITE_ATLAS_HEIGHT
        assertCorners(
            doubleArrayOf(u0, v0, u1, v0, u1, v1, u0, v1),
            icon.quad.cornersUv,
            "icon atlas corners",
            FLOAT_UNIT_TOLERANCE,
        )
    }

    /**
     * `text-translate` moves the text and **not** the icon, because the two are independent style
     * properties of one symbol.
     *
     * The candidate carries a `text-translate` of `(23, -17)` and the icon carries no translate of
     * its own, so the label's anchor must move by that displacement while the icon's centre stays on
     * the projected geographic anchor. Asserting both halves in one case is what makes it a
     * statement about *which* anchor the icon was given: an icon handed the text's translated anchor
     * would land exactly 23 right and 17 up of where it belongs, and an assertion on the icon alone
     * could not tell that from the projection being wrong.
     */
    @Test
    fun theIconIsPlacedAtTheSymbolAnchorRatherThanTheTextTranslatedOne() {
        val camera = resolvedPlacementCamera()
        val anchor = projectedAnchor(camera)
        val placed = placeLabels(
            camera,
            placementBatch(
                placementCandidate(translateX = 23.0, translateY = -17.0).withIcon(iconRef()),
            ),
            SPRITE_MANIFEST,
        ).single()

        assertClose(anchor.pixelX + 23.0, placed.anchorPixelX)
        assertClose(anchor.pixelY - 17.0, placed.anchorPixelY)

        val frame = recoverIconFrame(assertNotNull(placed.icon).quad)
        assertClose(anchor.pixelX, frame.centreX, FLOAT_PIXEL_TOLERANCE)
        assertClose(anchor.pixelY, frame.centreY, FLOAT_PIXEL_TOLERANCE)
    }

    /**
     * `icon-translate` is resolved in the frame `icon-translate-anchor` names -- the map's or the
     * viewport's -- and is **never** turned by the icon's own rotation.
     *
     * The camera faces east, where the map's basis is a quarter turn anticlockwise on screen, so a
     * map-aligned `(19, -8)` must land at `(-8, -19)` while the viewport-aligned control lands at
     * `(19, -8)`. The two are different in both axes and in one sign, so a translate routed through
     * the wrong frame cannot land on the right pixel.
     *
     * The third icon is the same map-aligned translate with a 50 degree `icon-rotate` on it. Its
     * anchor shift and offset are both zero, so the rotation has nothing of its own to turn and the
     * centre must not move at all: a translate folded into the rotation would swing it 50 degrees.
     */
    @Test
    fun anIconTranslateResolvesInItsDeclaredFrameAndIsNotTurnedByTheIconRotation() {
        val facingEast = resolvedPlacementCamera(placementCamera(bearing = 90.0))
        val anchor = projectedAnchor(facingEast)

        val withMap = recoverIconFrame(
            assertNotNull(
                placedIcon(
                    facingEast,
                    iconRef(translateX = 19.0, translateY = -8.0, translateAlignment = SymbolAlignment.MAP),
                ),
            ).quad,
        )
        assertClose(anchor.pixelX - 8.0, withMap.centreX, FLOAT_PIXEL_TOLERANCE)
        assertClose(anchor.pixelY - 19.0, withMap.centreY, FLOAT_PIXEL_TOLERANCE)

        val withViewport = recoverIconFrame(
            assertNotNull(
                placedIcon(
                    facingEast,
                    iconRef(translateX = 19.0, translateY = -8.0, translateAlignment = SymbolAlignment.VIEWPORT),
                ),
            ).quad,
        )
        assertClose(anchor.pixelX + 19.0, withViewport.centreX, FLOAT_PIXEL_TOLERANCE)
        assertClose(anchor.pixelY - 8.0, withViewport.centreY, FLOAT_PIXEL_TOLERANCE)

        val rotated = recoverIconFrame(
            assertNotNull(
                placedIcon(
                    facingEast,
                    iconRef(
                        translateX = 19.0,
                        translateY = -8.0,
                        translateAlignment = SymbolAlignment.MAP,
                        rotationDegrees = 50.0,
                    ),
                ),
            ).quad,
        )
        assertClose(withMap.centreX, rotated.centreX, FLOAT_PIXEL_TOLERANCE)
        assertClose(withMap.centreY, rotated.centreY, FLOAT_PIXEL_TOLERANCE)
    }

    /**
     * `icon-padding` expands the icon's half-extents **in the icon's own frame**, and the box is
     * oriented afterwards -- which is where Rentile's `OrientedCollisionBox` puts it.
     *
     * At 45 degrees the two orders are different rectangles and the difference is measurable:
     * padding first gives a screen half-width of `(13.5 + 4 + 7.5 + 4) / sqrt(2)`, about 20.5
     * pixels, while padding the oriented box's screen bound afterwards gives
     * `(13.5 + 7.5) / sqrt(2) + 4`, about 18.8. A right angle is the one rotation at which the two
     * agree exactly, which is why this case turns the icon by half of one.
     *
     * The unpadded control in the same case is what makes it a statement about the padding rather
     * than about the rotation.
     */
    @Test
    fun iconPaddingExpandsTheBoxBeforeItIsOriented() {
        val camera = resolvedPlacementCamera()
        val anchor = projectedAnchor(camera)
        val diagonal = 1.0 / kotlin.math.sqrt(2.0)

        val unpadded = assertNotNull(placedIcon(camera, iconRef(rotationDegrees = 45.0)))
        val bare = (ICON_WIDTH / 2.0 + ICON_HEIGHT / 2.0) * diagonal
        assertClose(anchor.pixelX - bare, unpadded.collisionBox.left, FLOAT_PIXEL_TOLERANCE)
        assertClose(anchor.pixelY + bare, unpadded.collisionBox.bottom, FLOAT_PIXEL_TOLERANCE)

        val padded = assertNotNull(placedIcon(camera, iconRef(rotationDegrees = 45.0, padding = 4.0)))
        val expanded = (ICON_WIDTH / 2.0 + 4.0 + ICON_HEIGHT / 2.0 + 4.0) * diagonal
        assertClose(anchor.pixelX - expanded, padded.collisionBox.left, FLOAT_PIXEL_TOLERANCE)
        assertClose(anchor.pixelX + expanded, padded.collisionBox.right, FLOAT_PIXEL_TOLERANCE)
        assertClose(anchor.pixelY - expanded, padded.collisionBox.top, FLOAT_PIXEL_TOLERANCE)
        assertClose(anchor.pixelY + expanded, padded.collisionBox.bottom, FLOAT_PIXEL_TOLERANCE)

        // Stated as an inequality as well, so that the case fails loudly if a future fixture ever
        // chose an angle at which the two orders happen to agree.
        assertTrue(
            expanded - bare > 4.0 + PADDING_ORDER_MARGIN,
            "at 45 degrees a padding applied before the turn must grow the bound by more than itself",
        )
    }

    // ---- rotation frames --------------------------------------------------------------------

    /**
     * `icon-rotation-alignment: auto` is `viewport` for a point symbol and `map` for a line symbol,
     * and the case proves it by triangulation rather than by restating either basis.
     *
     * Each placement runs three times -- `auto`, `map` and `viewport` -- and the assertion is that
     * `auto` equals one of the two explicit answers **and differs from the other**. The inequality
     * is what makes it non-vacuous: the fixture camera carries a 37.5 degree bearing and the line
     * runs diagonally on screen precisely so that the map basis is not the identity, and if either
     * ever became so, the "differs from" half fails rather than the case quietly passing.
     */
    @Test
    fun rotationAlignmentAutoIsViewportForAPointIconAndMapForALineIcon() {
        val camera = resolvedPlacementCamera()
        val point = SYMBOL_ALIGNMENTS.associateWith { alignment ->
            recoverIconFrame(
                assertNotNull(
                    placedIcon(camera, iconRef(rotationAlignment = alignment, rotationDegrees = 25.0)),
                ).quad,
            )
        }
        assertAxisEquals(point.getValue(SymbolAlignment.VIEWPORT), point.getValue(SymbolAlignment.AUTO))
        assertAxisDiffers(point.getValue(SymbolAlignment.MAP), point.getValue(SymbolAlignment.AUTO))

        val line = SYMBOL_ALIGNMENTS.associateWith { alignment ->
            recoverIconFrame(
                assertNotNull(
                    placeLabels(
                        camera,
                        placementBatch(
                            lineCandidate(line = LONG_LINE, placement = LabelPlacement.LINE_CENTER)
                                .withIcon(iconRef(rotationAlignment = alignment, rotationDegrees = 25.0)),
                        ),
                        SPRITE_MANIFEST,
                    ).single().icon,
                ).quad,
            )
        }
        assertAxisEquals(line.getValue(SymbolAlignment.MAP), line.getValue(SymbolAlignment.AUTO))
        assertAxisDiffers(line.getValue(SymbolAlignment.VIEWPORT), line.getValue(SymbolAlignment.AUTO))
    }

    /**
     * `icon-keep-upright` folds an icon that ended up pointing backwards by half a turn.
     *
     * `BACKWARD_LINE` is the long line traversed the other way, so its screen tangent points
     * leftward and a map-aligned icon inherits it. With the flag the icon's own +x must point
     * rightward; without it, leftward -- and the two axes must be **exact negations**, which is what
     * distinguishes a half turn from any other repair and from a fold that also mirrored the quad.
     *
     * The corners are checked as well as the axis: a fold that negated the basis but left the corner
     * order alone would produce the same axis and a quad wound the other way.
     */
    @Test
    fun keepUprightTurnsAMapAlignedLineIconTheRightWayUp() {
        val camera = resolvedPlacementCamera()
        val upright = recoverIconFrame(assertNotNull(backwardLineIcon(camera, keepUpright = true)).quad)
        val asDrawn = recoverIconFrame(assertNotNull(backwardLineIcon(camera, keepUpright = false)).quad)

        assertTrue(asDrawn.axisX < 0.0, "the backward line's tangent points leftward: ${asDrawn.axisX}")
        assertTrue(upright.axisX > 0.0, "keepUpright must turn it rightward: ${upright.axisX}")
        assertClose(-asDrawn.axisX, upright.axisX, RECOVERED_AXIS_TOLERANCE)
        assertClose(-asDrawn.axisY, upright.axisY, RECOVERED_AXIS_TOLERANCE)
        assertClose(asDrawn.centreX, upright.centreX, FLOAT_PIXEL_TOLERANCE)
        assertClose(asDrawn.centreY, upright.centreY, FLOAT_PIXEL_TOLERANCE)
    }

    /**
     * The two guards `icon-keep-upright` sits behind, each discriminated on its own.
     *
     * An icon rotated 170 degrees points leftward, so it is exactly the input the fold would act on
     * -- and it must be left alone in both of these cases:
     *
     *  - a **point** symbol, because a point icon's rotation comes from the author rather than from
     *    the geometry, and an author who asked for 170 degrees did not ask by accident;
     *  - a **viewport-aligned** line symbol, for the same reason.
     *
     * The case asserts the axis is still `(cos 170, sin 170)`, which is what a dropped guard would
     * negate. Dropping either guard alone fails exactly one of the two halves.
     */
    @Test
    fun keepUprightLeavesAnAuthoredRotationAlone() {
        val camera = resolvedPlacementCamera()
        val backwards = 170.0
        val cosine = cos(backwards * PI / 180.0)
        val sine = sin(backwards * PI / 180.0)
        assertTrue(cosine < 0.0, "the fixture rotation must be one the fold would act on")

        // A north-up camera, so the map basis is the identity and the composed rotation is exactly
        // the authored one -- which is what lets the two components be written down.
        val point = recoverIconFrame(
            assertNotNull(
                placedIcon(
                    resolvedPlacementCamera(placementCamera(bearing = 0.0)),
                    iconRef(
                        rotationDegrees = backwards,
                        rotationAlignment = SymbolAlignment.MAP,
                        keepUpright = true,
                    ),
                ),
            ).quad,
        )
        assertClose(cosine, point.axisX, RECOVERED_AXIS_TOLERANCE)
        assertClose(sine, point.axisY, RECOVERED_AXIS_TOLERANCE)

        val line = recoverIconFrame(
            assertNotNull(
                placeLabels(
                    camera,
                    placementBatch(
                        lineCandidate(line = LONG_LINE, placement = LabelPlacement.LINE_CENTER).withIcon(
                            iconRef(
                                rotationDegrees = backwards,
                                rotationAlignment = SymbolAlignment.VIEWPORT,
                                keepUpright = true,
                            ),
                        ),
                    ),
                    SPRITE_MANIFEST,
                ).single().icon,
            ).quad,
        )
        assertClose(cosine, line.axisX, RECOVERED_AXIS_TOLERANCE)
        assertClose(sine, line.axisY, RECOVERED_AXIS_TOLERANCE)
    }

    // ---- collision --------------------------------------------------------------------------

    /**
     * `symbol-avoid-edges` refuses an icon the viewport would clip, and admits one that merely sits
     * near the edge.
     *
     * The offset icon straddles the right edge by five pixels, so it still *intersects* the viewport
     * and would survive the ordinary cull -- which is what makes this a statement about
     * `avoidEdges` rather than about the cull. The two controls close the two ways it could pass
     * vacuously: the same icon with the flag off is placed, and an icon 20 pixels further in is
     * placed with the flag on.
     */
    @Test
    fun avoidEdgesRefusesAnIconTheViewportWouldClip() {
        val camera = resolvedPlacementCamera()
        val anchor = projectedAnchor(camera)
        val straddling = PLACEMENT_OUTPUT.width - anchor.pixelX - ICON_WIDTH / 2.0 + 5.0
        val inside = straddling - 20.0

        assertNull(
            placedIcon(camera, iconRef(offsetX = straddling, avoidEdges = true)),
            "an icon over the viewport edge is refused",
        )
        assertNotNull(
            placedIcon(camera, iconRef(offsetX = straddling, avoidEdges = false)),
            "the same icon without the flag is placed",
        )
        assertNotNull(
            placedIcon(camera, iconRef(offsetX = inside, avoidEdges = true)),
            "an icon wholly inside the viewport is placed with the flag on",
        )
    }

    /**
     * An icon claims collision space of its own, so a later label whose box lands on it is dropped
     * -- unless `icon-ignore-placement` says the icon is invisible to the pass.
     *
     * **The control is the point of the case.** The same later label placed against a symbol with no
     * icon at all must survive, because otherwise the drop could be the symbol's *text* doing the
     * work: the blocker's box sits 56 to 64 pixels above the anchor, clear of the 100-by-20 text box
     * and squarely inside the icon's, and this case is the executable statement of that.
     */
    @Test
    fun anIconClaimsCollisionSpaceUnlessItIgnoresPlacement() {
        val camera = resolvedPlacementCamera()
        val blocked = iconBlocker()

        assertEquals(
            listOf(0),
            placedIndices(camera, placementCandidate(sortKey = 5.0).withIcon(couplingIcon()), blocked),
            "a later label over the icon's box is dropped",
        )
        assertEquals(
            listOf(1, 0),
            placedIndices(camera, placementCandidate(sortKey = 5.0), blocked),
            "the same label survives when the symbol carries no icon",
        )
        assertEquals(
            listOf(1, 0),
            placedIndices(
                camera,
                placementCandidate(sortKey = 5.0).withIcon(couplingIcon(ignorePlacement = true)),
                blocked,
            ),
            "an icon that ignores placement blocks nothing",
        )
    }

    /**
     * A symbol's icon does not lose a collision with its own text.
     *
     * At the default anchor and offset the icon's 27-by-15 box sits wholly inside the label's
     * 100-by-20 one, so the two overlap by construction. Both halves must survive, which they can
     * only do if the pass queries both boxes **before** it inserts either: inserting the text first
     * would drop the icon, and -- since neither half is optional -- the coupling would then drop the
     * text along with it, leaving nothing placed at all.
     */
    @Test
    fun anIconDoesNotCollideWithItsOwnText() {
        val camera = resolvedPlacementCamera()
        val placed = placeLabels(
            camera,
            placementBatch(placementCandidate().withIcon(iconRef())),
            SPRITE_MANIFEST,
        ).single()

        assertTrue(placed.quads.isNotEmpty(), "the text survives")
        assertNotNull(placed.icon, "and so does the icon that overlaps it")
    }

    /**
     * `icon-overlap: always` places an icon on top of an occupied box; `never` yields to it.
     *
     * The blocker occupies the icon's own box and nothing else, so the `never` half loses the icon
     * and -- both halves being required -- the whole symbol with it, while the `always` half keeps
     * both. Running the pair in one case is what makes it a statement about `overlap` rather than
     * about the blocker.
     */
    @Test
    fun iconOverlapAlwaysPlacesOverAnOccupiedBox() {
        val camera = resolvedPlacementCamera()
        val blocker = iconBlocker(sortKey = 5.0)

        val always = placeLabels(
            camera,
            placementBatch(blocker, placementCandidate().withIcon(couplingIcon(overlap = SymbolOverlap.ALWAYS))),
            SPRITE_MANIFEST,
        )
        assertEquals(listOf(1, 0), always.map { it.candidateIndex })
        assertNotNull(always.first { it.candidateIndex == 1 }.icon, "an always-overlapping icon is placed")

        val never = placeLabels(
            camera,
            placementBatch(blocker, placementCandidate().withIcon(couplingIcon(overlap = SymbolOverlap.NEVER))),
            SPRITE_MANIFEST,
        )
        assertEquals(listOf(0), never.map { it.candidateIndex }, "a never-overlapping icon takes its text with it")
    }

    // ---- the coupling -----------------------------------------------------------------------

    /**
     * `text-optional` decides whether the **icon** survives text that could not be placed.
     *
     * The blocker occupies a rectangle inside the label's text box and clear of the icon's, so the
     * text fails collision and the icon does not -- which is the only arrangement in which this flag
     * decides anything. With it, the symbol survives as an icon with no text; without it, the whole
     * symbol goes.
     *
     * The blocker-free control asserts both halves place, which is what rules out the failure mode
     * a coupling case invites: a fixture whose text would not have placed anyway makes the
     * "dropped" half true for the wrong reason, and it is the reason the third assertion below
     * names both halves rather than just the label's presence.
     */
    @Test
    fun textOptionalDecidesWhetherTheIconSurvivesTextThatFailsCollision() {
        val camera = resolvedPlacementCamera()
        val blocker = textBlocker()
        val symbol = placementCandidate().withIcon(couplingIcon())

        val optional = placeLabels(
            camera,
            placementBatch(blocker, symbol.copy(textOptional = true)),
            SPRITE_MANIFEST,
        ).single { it.candidateIndex == 1 }
        assertTrue(optional.quads.isEmpty(), "the text lost its place")
        assertNotNull(optional.icon, "and the optional text let the icon stand alone")

        assertEquals(
            listOf(0),
            placeLabels(
                camera,
                placementBatch(blocker, symbol.copy(textOptional = false)),
                SPRITE_MANIFEST,
            ).map { it.candidateIndex },
            "a required text takes the icon with it",
        )

        val unblocked = placeLabels(
            camera,
            placementBatch(symbol.copy(textOptional = false)),
            SPRITE_MANIFEST,
        ).single()
        assertTrue(unblocked.quads.isNotEmpty(), "without the blocker the text places")
        assertNotNull(unblocked.icon, "and so does the icon")
    }

    /**
     * `icon-optional` decides whether the **text** survives an icon that could not be placed, which
     * is the mirror of [textOptionalDecidesWhetherTheIconSurvivesTextThatFailsCollision] and the
     * half the style specification's own wording is about: "text will display without their
     * corresponding icons when the icon collides with other symbols and the text does not".
     *
     * **Testing this flag against text that fails proves nothing**, which is why the blocker here
     * occupies the icon's box instead: with `icon-optional` the text stands alone, without it the
     * text is dropped too. Run the other way round the two settings agree, and the case would pass
     * under an implementation that ignored the flag entirely.
     */
    @Test
    fun iconOptionalDecidesWhetherTheTextSurvivesAnIconThatFailsCollision() {
        val camera = resolvedPlacementCamera()
        val blocker = iconBlocker(sortKey = 5.0)

        val optional = placeLabels(
            camera,
            placementBatch(blocker, placementCandidate().withIcon(couplingIcon(optional = true))),
            SPRITE_MANIFEST,
        ).single { it.candidateIndex == 1 }
        assertTrue(optional.quads.isNotEmpty(), "the text stands alone")
        assertNull(optional.icon, "the icon that lost its place is not carried")

        assertEquals(
            listOf(0),
            placeLabels(
                camera,
                placementBatch(blocker, placementCandidate().withIcon(couplingIcon(optional = false))),
                SPRITE_MANIFEST,
            ).map { it.candidateIndex },
            "a required icon takes its text with it",
        )

        val unblocked = placeLabels(
            camera,
            placementBatch(placementCandidate().withIcon(couplingIcon(optional = false))),
            SPRITE_MANIFEST,
        ).single()
        assertTrue(unblocked.quads.isNotEmpty(), "without the blocker the text places")
        assertNotNull(unblocked.icon, "and so does the icon")
    }

    /**
     * The four ways an icon fails to *exist*, none of which may delete the text it belongs to.
     *
     * This is the sharp edge of the coupling. A non-optional icon that lost a collision takes its
     * text with it -- that is the previous case -- but an icon that was never resolvable is not an
     * icon that failed to place: `hasIcon` is false, the text is vacuously able to stand alone, and
     * a layer whose sprite is missing from the atlas must still show its names. Every candidate here
     * carries `icon-optional: false`, so an implementation that conflated the two would lose the
     * label entirely.
     *
     * The control is the same candidate with a resolvable icon, asserting that the icon is *present*
     * -- without it, every assertion below would hold under an implementation that never resolved
     * any icon at all.
     */
    @Test
    fun anIconThatCannotBeResolvedLeavesItsTextAlone() {
        val camera = resolvedPlacementCamera()
        val symbol = placementCandidate().withIcon(iconRef(optional = false))

        assertNotNull(
            placeLabels(camera, placementBatch(symbol), SPRITE_MANIFEST).single().icon,
            "the control resolves",
        )

        val unresolvable = mapOf(
            "no retained manifest" to (symbol to null),
            "no entry under that name" to (symbol.withIcon(iconRef(imageName = "absent")) to SPRITE_MANIFEST),
            "an empty manifest" to (symbol to SpriteAtlasManifest(SPRITE_ATLAS_WIDTH, SPRITE_ATLAS_HEIGHT, emptyMap(), ByteArray(0))),
            "a zero-dimension atlas" to (symbol to SpriteAtlasManifest(0, 0, SPRITE_MANIFEST.entries, ByteArray(0))),
        )
        for ((description, fixture) in unresolvable) {
            val placed = placeLabels(camera, placementBatch(fixture.first), fixture.second).single()
            assertNull(placed.icon, "$description yields no icon")
            assertTrue(placed.quads.isNotEmpty(), "$description leaves the text placed")
        }
    }

    /**
     * An icon whose placement inputs are not usable numbers is dropped, and -- as above -- its text
     * is not.
     *
     * Each row moves exactly one field off a value the control uses, so a guard that dropped the
     * wrong field would leave one row placing. The control asserting the icon is present is what
     * stops the whole table passing vacuously against an implementation that resolved nothing.
     */
    @Test
    fun anIconWithAnUnusablePlacementInputIsDropped() {
        val camera = resolvedPlacementCamera()
        assertNotNull(placedIcon(camera, iconRef()), "the control resolves")

        val unusable = mapOf(
            "a zero width" to iconRef(width = 0.0),
            "a negative height" to iconRef(height = -15.0),
            "a non-finite offset" to iconRef(offsetX = Double.NaN),
            "a non-finite translate" to iconRef(translateY = Double.POSITIVE_INFINITY),
            "a non-finite rotation" to iconRef(rotationDegrees = Double.NaN),
            "a negative padding" to iconRef(padding = -1.0),
            "a negative opacity" to iconRef(opacity = -0.5),
            "a non-finite halo width" to iconRef(haloWidth = Double.NaN),
            "a negative halo blur" to iconRef(haloBlur = -2.0),
        )
        for ((description, ref) in unusable) {
            val placed = placeLabels(camera, placementBatch(placementCandidate().withIcon(ref)), SPRITE_MANIFEST)
                .single()
            assertNull(placed.icon, "$description yields no icon")
            assertTrue(placed.quads.isNotEmpty(), "$description leaves the text placed")
        }
    }

    // ---- paint ------------------------------------------------------------------------------

    /**
     * The icon's paint, straight and per candidate, with the entry's `sdf` flag on it.
     *
     * All eight colour bytes differ, so a channel read in the wrong order lands somewhere else, and
     * the fill and halo colours share none of their bytes so one cannot stand in for the other.
     *
     * **[ResolvedIconPaint.tintable] is the field that decides whether the other four mean
     * anything.** Rentile tints a sprite under `SRC_IN` when the manifest entry says `sdf` and
     * passes no colour filter otherwise, so a consumer that tinted every sprite would repaint
     * artwork the style never asked to recolour. The case reads the same paint off both entries in
     * the fixture atlas, which is what makes it a statement about the entry rather than a constant.
     */
    @Test
    fun theIconPaintCarriesTheCandidateColoursAndTheEntrySdfFlag() {
        val camera = resolvedPlacementCamera()
        val paint = assertNotNull(
            placedIcon(
                camera,
                iconRef(
                    color = 0x99213243.toInt(),
                    haloColor = 0xC8546576.toInt(),
                    opacity = 0.625,
                    haloWidth = 2.5,
                    haloBlur = 1.25,
                ),
            ),
        ).quad.paint

        assertCorners(
            doubleArrayOf(0x21 / 255.0, 0x32 / 255.0, 0x43 / 255.0, 0x99 / 255.0),
            paint.colour,
            "icon colour",
            FLOAT_UNIT_TOLERANCE,
        )
        assertCorners(
            doubleArrayOf(0x54 / 255.0, 0x65 / 255.0, 0x76 / 255.0, 0xC8 / 255.0),
            paint.haloColour,
            "icon halo colour",
            FLOAT_UNIT_TOLERANCE,
        )
        assertClose(0.625, paint.opacity.toDouble(), FLOAT_UNIT_TOLERANCE)
        assertClose(2.5, paint.haloWidthPixels.toDouble(), FLOAT_UNIT_TOLERANCE)
        assertClose(1.25, paint.haloBlurPixels.toDouble(), FLOAT_UNIT_TOLERANCE)

        assertEquals(false, paint.tintable, "the ordinary sprite entry is not a distance field")
        assertEquals(
            true,
            assertNotNull(placedIcon(camera, iconRef(imageName = SDF_SPRITE_NAME))).quad.paint.tintable,
            "the sdf entry is",
        )
    }

    // ---- line placement ---------------------------------------------------------------------

    /**
     * Every repeat of a line-placed symbol carries its **own** icon, at its own anchor.
     *
     * A road name repeated along a road is several symbols, and a shield drawn once at the
     * candidate's midpoint would belong to none of them. The case asserts one icon per repeat, that
     * no two land on the same pixel, and -- the geometric half, which owes nothing to how the icon
     * got there -- that every icon's centre lies **on the projected polyline**, measured by the line
     * fixture's own point-to-segment instrument.
     */
    @Test
    fun everyLineRepeatCarriesItsOwnIconAtItsOwnAnchor() {
        val camera = resolvedPlacementCamera()
        val run = screenRunOf(camera, LONG_LINE)
        // The projected run is about 150 pixels long, so a spacing wide enough to keep the repeats
        // from colliding would leave only one of them -- and one repeat cannot tell an icon placed
        // per repeat from an icon placed per candidate. Both halves are therefore permitted to
        // overlap, which is what leaves the case with several repeats to compare.
        val placed = placeLabels(
            camera,
            placementBatch(
                lineCandidate(
                    line = LONG_LINE,
                    symbolSpacing = 30.0,
                    glyphs = shortLineGlyphRow(),
                    overlap = SymbolOverlap.ALWAYS,
                ).withIcon(iconRef(overlap = SymbolOverlap.ALWAYS)),
            ),
            SPRITE_MANIFEST,
        )

        assertTrue(placed.size >= 2, "the fixture must repeat, or the case says nothing: ${placed.size}")
        val centres = placed.map { recoverIconFrame(assertNotNull(it.icon, "every repeat carries an icon").quad) }
        for (centre in centres) {
            assertTrue(
                run.nearestOn(centre.centreX, centre.centreY).offLine < ON_LINE_TOLERANCE,
                "an icon centre must sit on the line, was ${run.nearestOn(centre.centreX, centre.centreY).offLine}",
            )
        }
        val distances = centres.map { run.nearestOn(it.centreX, it.centreY).alongLine }.sorted()
        for (index in 1 until distances.size) {
            assertTrue(
                distances[index] - distances[index - 1] > 1.0,
                "two repeats must not share one anchor: $distances",
            )
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun placedIcon(
        camera: ResolvedMercatorCamera,
        icon: LabelIconRef,
        sprites: SpriteAtlasManifest? = SPRITE_MANIFEST,
        candidate: LabelCandidate = placementCandidate(),
    ): PlacedIcon? = placeLabels(camera, placementBatch(candidate.withIcon(icon)), sprites)
        .singleOrNull()
        ?.icon

    private fun backwardLineIcon(
        camera: ResolvedMercatorCamera,
        keepUpright: Boolean,
    ): PlacedIcon? = placeLabels(
        camera,
        placementBatch(
            lineCandidate(line = BACKWARD_LINE, placement = LabelPlacement.LINE_CENTER).withIcon(
                iconRef(rotationAlignment = SymbolAlignment.MAP, keepUpright = keepUpright),
            ),
        ),
        SPRITE_MANIFEST,
    ).single().icon

    private fun placedIndices(
        camera: ResolvedMercatorCamera,
        vararg candidates: LabelCandidate,
    ): List<Int> = placeLabels(camera, placementBatch(*candidates), SPRITE_MANIFEST).map { it.candidateIndex }

    /**
     * A rectangle inside the shared text box and clear of the coupling icon's, at a priority that
     * places it first.
     */
    private fun textBlocker(sortKey: Double = 5.0): LabelCandidate =
        placementCandidate(sortKey = sortKey, left = 0.0, top = -5.0, right = 40.0, bottom = 5.0)

    /**
     * A rectangle inside the coupling icon's box and clear of the shared text box, which sits 56 to
     * 64 pixels above the anchor -- above the text box's own top edge at 11.
     */
    private fun iconBlocker(sortKey: Double = 0.0): LabelCandidate =
        placementCandidate(sortKey = sortKey, left = -5.0, top = -64.0, right = 5.0, bottom = -56.0)

    private fun assertAxisEquals(expected: RecoveredIconFrame, actual: RecoveredIconFrame) {
        assertClose(expected.axisX, actual.axisX, RECOVERED_AXIS_TOLERANCE)
        assertClose(expected.axisY, actual.axisY, RECOVERED_AXIS_TOLERANCE)
    }

    private fun assertAxisDiffers(unexpected: RecoveredIconFrame, actual: RecoveredIconFrame) {
        assertTrue(
            abs(unexpected.axisX - actual.axisX) > AXIS_DIFFERENCE_FLOOR ||
                abs(unexpected.axisY - actual.axisY) > AXIS_DIFFERENCE_FLOOR,
            "the two frames must differ, or the case proves nothing: " +
                "(${unexpected.axisX}, ${unexpected.axisY}) and (${actual.axisX}, ${actual.axisY})",
        )
    }

    private fun edgeLength(quad: ResolvedIconQuad, from: Int, to: Int): Double {
        val stepX = (quad.cornersXy[to * 2] - quad.cornersXy[from * 2]).toDouble()
        val stepY = (quad.cornersXy[to * 2 + 1] - quad.cornersXy[from * 2 + 1]).toDouble()
        return kotlin.math.sqrt(stepX * stepX + stepY * stepY)
    }

    private fun assertCorners(
        expected: DoubleArray,
        actual: FloatArray,
        description: String,
        tolerance: Double = FLOAT_PIXEL_TOLERANCE,
    ) {
        assertEquals(expected.size, actual.size, "$description component count")
        for (index in expected.indices) {
            assertTrue(
                abs(expected[index] - actual[index]) <= tolerance,
                "$description [$index]: expected ${expected[index]} but was ${actual[index]}",
            )
        }
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = DOUBLE_TOLERANCE) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected $expected but was $actual")
    }

    private companion object {
        val SYMBOL_ALIGNMENTS: List<SymbolAlignment> =
            listOf(SymbolAlignment.MAP, SymbolAlignment.VIEWPORT, SymbolAlignment.AUTO)

        /** Not a multiple of a right angle, so no axis maps onto another under it. */
        const val ORDERING_ROTATION_DEGREES: Double = 35.0

        /** A quad corner is a `Float`; a pixel near 700 carries about 6e-5 of representation. */
        const val FLOAT_PIXEL_TOLERANCE: Double = 1e-3

        /** An atlas coordinate is a `Float` in `[0, 1]`, where the representation is far finer. */
        const val FLOAT_UNIT_TOLERANCE: Double = 1e-6

        /**
         * A rotation basis **recovered from two `Float` corners** near pixel 700 and divided by the
         * icon's 27-pixel width, which carries about 4e-6 of representation. Nothing asserted at this
         * tolerance is a near miss: a negated basis is out by 2, an alignment read from the wrong
         * frame by more than 0.05, and a transposed axis by the whole of one component.
         */
        const val RECOVERED_AXIS_TOLERANCE: Double = 1e-4

        /** Collision boxes stay in `Double`. */
        const val DOUBLE_TOLERANCE: Double = 1e-9

        /**
         * How far apart two rotation bases must be before the case will call them different. Far
         * above `Float` noise and far below the smallest difference any of the fixtures produce.
         */
        const val AXIS_DIFFERENCE_FLOOR: Double = 0.05

        /** How far off the polyline an icon centre may sit before the case calls it off the line. */
        const val ON_LINE_TOLERANCE: Double = 0.05

        /** Margin above which the padded-then-oriented bound must exceed the padding itself. */
        const val PADDING_ORDER_MARGIN: Double = 1.0
    }
}

/** [LabelCandidate] with an icon on it, so a case reads as the icon it is about. */
internal fun LabelCandidate.withIcon(icon: LabelIconRef): LabelCandidate = copy(icon = icon)
