package com.rohittp.reng.internal.label

import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.rentile.LabelPlacement
import com.rohittp.rentile.SymbolOverlap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **E-terrain task 18: a map label follows the ground under it.**
 *
 * A label anchored at sea level detaches from the feature it names the moment the terrain under it
 * rises -- it is drawn where the ellipsoid projects to, and the feature is drawn where the displaced
 * ground put it. These cases are about the *placement pass*, which is where the height enters:
 * `placeLabels` projects the anchor, and everything after that -- the glyph frames, the collision
 * box, the icon's box and quad, and the collision index itself -- is a function of the pixel it
 * produced.
 *
 * **Why the height enters here rather than at the draw, asserted rather than asserted-about.**
 * E-labels' decision E4 resolves collision once per `prepare()` and never during a draw.
 * [collisionResolvesAgainstTheElevatedBoxesRatherThanTheSeaLevelOnes] is the case that can tell the
 * two apart: two labels that do not overlap on the ellipsoid do overlap once one of them rides a
 * ridge, and a build that moved the labels *after* placing them would keep both. Every other case
 * here would pass under that build.
 *
 * **Three symmetry points this cycle has already been caught by, and where each is closed.**
 *  - *A constant elevation cannot tell a per-anchor lookup from a per-frame one.*
 *    [twoAnchorsOverDifferentGroundEachRideTheirOwnHeight] gives the two anchors genuinely different
 *    heights, and [aLineLabelRidesEverySourcePointRatherThanOneHeightForTheWholeLine] compares a ramp
 *    against the constant that a per-line lookup would have used.
 *  - *An assertion satisfiable by an empty frame.* Every case asserts what it placed before it
 *    asserts where, and the collision case asserts both survivors in its own control arm.
 *  - *A zero shift read as agreement.* Every "moves" case carries a floor on how far it moved, and
 *    the floor is measured from the fixture's own projection rather than picked.
 *
 * **What these cases deliberately do not gate.** They inject a [LabelGroundElevation] rather than a
 * `GroundSurface`, so nothing here says a *real* frame's labels reach a real DEM -- that is
 * `RendererLabelTerrainTest`'s, and it is the half E-labels paid most for ("the label path was fully
 * built and wired to nothing"). Nor do they say anything about which ground cell a height comes from
 * or which diagonal it is interpolated across, which is task 16's and is gated by `GroundSurfaceTest`
 * against a DEM with relief inside a single tile.
 */
class LabelTerrainPlacementTest {

    /**
     * The headline, in both directions at once: over a ridge the anchor lands where the same anchor
     * *declared* at that altitude lands, and over flat ground it does not move by a bit.
     *
     * The expectation is the fixture's own projection of `PLACEMENT_ANCHOR` at [RIDGE_METRES], which
     * is an independent derivation of everything except the number itself -- and the number is
     * exactly what the terrain lookup is for. [MINIMUM_RIDGE_SHIFT_PIXELS] is what stops "landed
     * where predicted" from being satisfied by a lookup that changed nothing.
     */
    @Test fun aLabelOverARidgeMovesWithItAndOverFlatGroundDoesNotMoveAtAll() {
        val camera = resolvedPlacementCamera()
        val batch = placementBatch(placementCandidate())

        val flat = assertNotNull(placeLabels(camera, batch).singleOrNull(), "the fixture places one label")
        val ridden = assertNotNull(
            placeLabels(camera, batch, ground = LabelGroundElevation { _, _ -> RIDGE_METRES }).singleOrNull(),
            "a label over terrain is still one label",
        )

        val expected = projectedAnchor(camera, PLACEMENT_ANCHOR.copy(altitudeMetres = RIDGE_METRES))
        assertEquals(expected.pixelX, ridden.anchorPixelX, PIXEL_TOLERANCE, "the anchor rides the ground in x")
        assertEquals(expected.pixelY, ridden.anchorPixelY, PIXEL_TOLERANCE, "the anchor rides the ground in y")

        val shift = distance(flat.anchorPixelX, flat.anchorPixelY, ridden.anchorPixelX, ridden.anchorPixelY)
        assertTrue(
            shift >= MINIMUM_RIDGE_SHIFT_PIXELS,
            "$RIDGE_METRES m of terrain must move the label further than $MINIMUM_RIDGE_SHIFT_PIXELS " +
                "pixels, and it moved $shift -- a lookup that answered zero would satisfy the two " +
                "equalities above and nothing else",
        )

        // Flat ground is not "nearly unmoved": a zero height must reach the projection as the literal
        // zero every frame RenG has ever drawn used, so this is an exact comparison rather than a
        // tolerance. It is the case that says the 28 corpus styles with no `terrain` block are
        // byte-identical to before this cycle.
        val overFlatGround = assertNotNull(
            placeLabels(camera, batch, ground = LabelGroundElevation { _, _ -> 0.0 }).singleOrNull(),
        )
        assertEquals(flat.anchorPixelX, overFlatGround.anchorPixelX, "flat ground moves nothing in x")
        assertEquals(flat.anchorPixelY, overFlatGround.anchorPixelY, "flat ground moves nothing in y")
        assertEquals(flat.collisionBox, overFlatGround.collisionBox, "and claims exactly the same space")
    }

    /**
     * Two anchors over two different heights, each landing at its own.
     *
     * A build that sampled the terrain once per frame -- at the camera centre, say -- would move both
     * labels by one shift and pass every case that carries a single anchor. Here the two heights
     * differ by [SOUTHERN_METRES] against [RIDGE_METRES] and the assertion is per label.
     */
    @Test fun twoAnchorsOverDifferentGroundEachRideTheirOwnHeight() {
        val camera = resolvedPlacementCamera()
        val batch = placementBatch(
            placementCandidate(sortKey = 2.0, overlap = SymbolOverlap.ALWAYS),
            placementCandidate(sortKey = 1.0, overlap = SymbolOverlap.ALWAYS, position = SOUTHERN_ANCHOR),
        )
        val ground = LabelGroundElevation { latitude, _ ->
            if (latitude >= PLACEMENT_ANCHOR.latitude) RIDGE_METRES else SOUTHERN_METRES
        }

        val placed = placeLabels(camera, batch, ground = ground)
        assertEquals(2, placed.size, "both candidates overlap-always, so both are placed")

        val northern = assertNotNull(placed.firstOrNull { it.candidateIndex == 0 })
        val southern = assertNotNull(placed.firstOrNull { it.candidateIndex == 1 })
        val expectedNorthern = projectedAnchor(camera, PLACEMENT_ANCHOR.copy(altitudeMetres = RIDGE_METRES))
        val expectedSouthern = projectedAnchor(camera, SOUTHERN_ANCHOR.copy(altitudeMetres = SOUTHERN_METRES))

        assertEquals(expectedNorthern.pixelX, northern.anchorPixelX, PIXEL_TOLERANCE, "northern x")
        assertEquals(expectedNorthern.pixelY, northern.anchorPixelY, PIXEL_TOLERANCE, "northern y")
        assertEquals(expectedSouthern.pixelX, southern.anchorPixelX, PIXEL_TOLERANCE, "southern x")
        assertEquals(expectedSouthern.pixelY, southern.anchorPixelY, PIXEL_TOLERANCE, "southern y")

        // The two heights have to produce two *distinguishable* pictures or the case above is a pair
        // of tautologies: a build that gave both anchors the northern height would still satisfy one
        // of them, and this is what refuses the other.
        val southernAtNorthernHeight =
            projectedAnchor(camera, SOUTHERN_ANCHOR.copy(altitudeMetres = RIDGE_METRES))
        val betweenHeights = distance(
            expectedSouthern.pixelX,
            expectedSouthern.pixelY,
            southernAtNorthernHeight.pixelX,
            southernAtNorthernHeight.pixelY,
        )
        assertTrue(
            betweenHeights >= MINIMUM_RIDGE_SHIFT_PIXELS,
            "the two heights must be worth more than $MINIMUM_RIDGE_SHIFT_PIXELS pixels at the " +
                "southern anchor, and they are worth $betweenHeights",
        )
    }

    /**
     * **The case that says the height is spent before collision rather than after it.**
     *
     * Two symbols, each claiming a square box, positioned so that on the ellipsoid they are a clear
     * [MINIMUM_RIDGE_SHIFT_PIXELS]-plus apart and on the terrain they land on the same pixel. The
     * second candidate's box is offset by the *measured* shift the first one's anchor takes, so the
     * fixture carries no tuned constant: it is derived from the projection it is about.
     *
     * The control arm is the same batch with no terrain at all, where both survive. Without it "one
     * label survived" would also be what a build that dropped every label produced.
     */
    @Test fun collisionResolvesAgainstTheElevatedBoxesRatherThanTheSeaLevelOnes() {
        val camera = resolvedPlacementCamera()
        val flatAnchor = projectedAnchor(camera, PLACEMENT_ANCHOR)
        val riddenAnchor = projectedAnchor(camera, PLACEMENT_ANCHOR.copy(altitudeMetres = RIDGE_METRES))
        val southernAnchor = projectedAnchor(camera, SOUTHERN_ANCHOR)

        val shiftX = riddenAnchor.pixelX - flatAnchor.pixelX
        val shiftY = riddenAnchor.pixelY - flatAnchor.pixelY
        val shift = sqrt(shiftX * shiftX + shiftY * shiftY)
        assertTrue(shift >= MINIMUM_RIDGE_SHIFT_PIXELS, "the ridge must be worth a real shift: $shift")

        // Half the claimed box. At a quarter of the shift the two boxes are 2 * half = shift / 2
        // apart along their longer axis at most, and their centres are `shift` apart in total, so on
        // the ellipsoid they cannot touch -- while on the terrain their centres coincide exactly.
        val half = shift / 4.0
        assertTrue(
            insideViewport(riddenAnchor.pixelX, riddenAnchor.pixelY, half),
            "the ridden anchor and its box must stay on a ${PLACEMENT_OUTPUT.width} by " +
                "${PLACEMENT_OUTPUT.height} screen, and it is at " +
                "(${riddenAnchor.pixelX}, ${riddenAnchor.pixelY})",
        )
        assertTrue(
            insideViewport(flatAnchor.pixelX, flatAnchor.pixelY, half),
            "and so must the sea-level one, at (${flatAnchor.pixelX}, ${flatAnchor.pixelY})",
        )

        val batch = placementBatch(
            // The winner: a higher `symbol-sort-key` reaches the collision index first.
            placementCandidate(sortKey = 5.0, left = -half, top = -half, right = half, bottom = half),
            // The loser, anchored elsewhere entirely, with a box that sits exactly where the winner's
            // box lands once the winner rides the ridge.
            placementCandidate(
                sortKey = 1.0,
                position = SOUTHERN_ANCHOR,
                left = riddenAnchor.pixelX - southernAnchor.pixelX - half,
                top = riddenAnchor.pixelY - southernAnchor.pixelY - half,
                right = riddenAnchor.pixelX - southernAnchor.pixelX + half,
                bottom = riddenAnchor.pixelY - southernAnchor.pixelY + half,
            ),
        )

        val overFlatGround = placeLabels(camera, batch)
        assertEquals(
            setOf(0, 1),
            overFlatGround.map { it.candidateIndex }.toSet(),
            "on the ellipsoid the two boxes are $shift pixels apart and both must survive",
        )

        val ground = LabelGroundElevation { latitude, _ ->
            // Only the northern anchor rides anything: the southern label's box is already where the
            // collision happens, and moving it too would be a fixture that collides for two reasons.
            if (latitude >= PLACEMENT_ANCHOR.latitude) RIDGE_METRES else 0.0
        }
        val overTerrain = placeLabels(camera, batch, ground = ground)
        assertEquals(
            listOf(0),
            overTerrain.map { it.candidateIndex },
            "on the terrain the winner lands inside the loser's box, so the lower sort key loses " +
                "its place -- which a build that moved labels after collision could not produce",
        )
    }

    /**
     * A symbol's icon rides the ground with its own text, by exactly the same screen vector.
     *
     * **The two halves are placed by two different functions from one anchor**, and the icon's is
     * handed the *untranslated* projected anchor while the text's is handed the translated one, so
     * an implementation that elevated one projection and not the other compiles and draws a symbol
     * whose shield has slid off its name. The assertion is on the delta rather than on the position,
     * because the two halves legitimately sit at different pixels.
     */
    @Test fun anIconRidesTheGroundByTheSameVectorItsOwnTextDoes() {
        val camera = resolvedPlacementCamera()
        val batch = placementBatch(placementCandidate().copy(icon = iconRef()))

        val flat = assertNotNull(placeLabels(camera, batch, SPRITE_MANIFEST).singleOrNull())
        val ridden = assertNotNull(
            placeLabels(
                camera,
                batch,
                SPRITE_MANIFEST,
                LabelGroundElevation { _, _ -> RIDGE_METRES },
            ).singleOrNull(),
        )

        val flatIcon = assertNotNull(flat.icon, "the fixture's symbol carries an icon")
        val riddenIcon = assertNotNull(ridden.icon, "and still carries one over terrain")
        assertTrue(flat.quads.isNotEmpty() && ridden.quads.isNotEmpty(), "and carries its text too")

        val textShiftX = ridden.anchorPixelX - flat.anchorPixelX
        val textShiftY = ridden.anchorPixelY - flat.anchorPixelY
        assertTrue(
            distance(0.0, 0.0, textShiftX, textShiftY) >= MINIMUM_RIDGE_SHIFT_PIXELS,
            "the text has to move for this case to mean anything",
        )

        assertEquals(
            textShiftX,
            riddenIcon.collisionBox.left - flatIcon.collisionBox.left,
            PIXEL_TOLERANCE,
            "the icon's claimed space rides the ground with its text in x",
        )
        assertEquals(
            textShiftY,
            riddenIcon.collisionBox.top - flatIcon.collisionBox.top,
            PIXEL_TOLERANCE,
            "the icon's claimed space rides the ground with its text in y",
        )
        // The claim and the ink separately: an icon that reserved the right space and drew in the old
        // one is the defect E-labels found by drawing nothing at all, in a subtler form.
        for (corner in 0 until ICON_QUAD_FLOATS step 2) {
            assertEquals(
                textShiftX.toFloat(),
                riddenIcon.quad.cornersXy[corner] - flatIcon.quad.cornersXy[corner],
                FLOAT_PIXEL_TOLERANCE,
                "icon corner ${corner / 2} rides the ground in x",
            )
            assertEquals(
                textShiftY.toFloat(),
                riddenIcon.quad.cornersXy[corner + 1] - flatIcon.quad.cornersXy[corner + 1],
                FLOAT_PIXEL_TOLERANCE,
                "icon corner ${corner / 2} rides the ground in y",
            )
        }
    }

    /**
     * A line label is sampled at every source point, not once for the whole line.
     *
     * **The claim is "no single height reproduces this row", and it is asserted as exactly that** --
     * against a family of constants spanning the fixture's whole relief, rather than against one
     * chosen constant. That is a repair rather than a first draft, and mutation is what forced it.
     * The first version compared a rising ramp with one averaged height and required the per-glyph
     * disagreement to *spread*; a build that sampled the line's first point and used that height for
     * every vertex passed, because any two constant heights already disagree by a few pixels across a
     * fifty-pixel row -- a perspective camera scales a raised row about its own centre. The second
     * version required the row's two end glyphs to move in opposite directions, and that failed on
     * the correct build: a `line-center` anchor sits at half the *projected* run, which the tilt
     * moves, so both ends of the row move together and the sign says nothing.
     *
     * What survives both is a comparison of the row's **shape about its own anchor**, which is
     * immune to the anchor moving, against every height the fixture's terrain takes. Under a build
     * that samples once, the road is at one height and one of these arms reproduces it exactly.
     *
     * `keepUpright` is off here so that a flipped reading order cannot be mistaken for a bent row.
     */
    @Test fun aLineLabelRidesEverySourcePointRatherThanOneHeightForTheWholeLine() {
        val camera = resolvedPlacementCamera()
        val batch = placementBatch(
            lineCandidate(placement = LabelPlacement.LINE_CENTER, keepUpright = false),
        )

        val tilted = assertNotNull(
            placeLabels(camera, batch, ground = ::tiltedRampMetres).singleOrNull(),
            "a `line-center` candidate places exactly one instance, over a tilted road too",
        )
        assertTrue(tilted.quads.size >= MINIMUM_LINE_GLYPHS, "and the row has glyphs to compare")

        val tiltedShape = rowShapeAboutItsAnchor(tilted)
        val nearest = CONSTANT_HEIGHTS_METRES.minOf { metres ->
            val arm = assertNotNull(
                placeLabels(camera, batch, ground = LabelGroundElevation { _, _ -> metres }).singleOrNull(),
                "the road at a constant $metres m still places its one instance",
            )
            assertEquals(
                tilted.quads.size,
                arm.quads.size,
                "every arm draws the same glyph row, so a difference below is geometry, not content",
            )
            shapeDistance(tiltedShape, rowShapeAboutItsAnchor(arm))
        }

        assertTrue(
            nearest >= MINIMUM_RIDGE_SHIFT_PIXELS,
            "no single height may reproduce a road that sinks at one end and rises at the other, and " +
                "the closest of ${CONSTANT_HEIGHTS_METRES.size} constants spanning " +
                "${CONSTANT_HEIGHTS_METRES.first()}..${CONSTANT_HEIGHTS_METRES.last()} m came within " +
                "$nearest pixels of it, under a floor of $MINIMUM_RIDGE_SHIFT_PIXELS",
        )
    }

    /**
     * Two placements of one batch over one terrain answer identically -- the guard E-labels' decision
     * E4 rests on, restated now that a placement reads something outside the batch.
     *
     * A frame prepared twice must collide twice the same way or its labels flicker, and the
     * elevation is the newest input that could have made it not. Everything a [PlacedLabel] carries
     * is compared, including the survivors' order, because the order is what the draw paints in.
     */
    @Test fun twoPlacementsOfOneBatchOverOneTerrainAreIdentical() {
        val camera = resolvedPlacementCamera()
        val batch = placementBatch(
            placementCandidate(sortKey = 5.0),
            placementCandidate(sortKey = 1.0, position = SOUTHERN_ANCHOR),
            lineCandidate(placement = LabelPlacement.LINE),
        )

        val first = placeLabels(camera, batch, SPRITE_MANIFEST, ::rampMetres)
        val second = placeLabels(camera, batch, SPRITE_MANIFEST, ::rampMetres)

        assertTrue(first.isNotEmpty(), "the fixture places something, or the equality below is empty")
        assertEquals(first.map(::shapeOf), second.map(::shapeOf), "two placements answer identically")
    }
}

/** Everything about a placed label that a second placement must reproduce exactly. */
private fun shapeOf(label: PlacedLabel): List<Double> = listOf(
    label.candidateIndex.toDouble(),
    label.lineRepeat?.runIndex?.toDouble() ?: -1.0,
    label.lineRepeat?.anchorDistancePixels ?: -1.0,
    label.anchorPixelX,
    label.anchorPixelY,
    label.collisionBox.left,
    label.collisionBox.top,
    label.collisionBox.right,
    label.collisionBox.bottom,
    label.quads.size.toDouble(),
    label.quads.sumOf { it.cornersXy.sum().toDouble() },
    label.icon?.quad?.cornersXy?.sum()?.toDouble() ?: -1.0,
)

/**
 * One placed instance's glyph corners measured from its own anchor, which is what makes a comparison
 * between two arms a comparison of *shape*.
 *
 * A `line-center` anchor sits at half the projected run's length, and terrain changes that length --
 * so two arms legitimately anchor at two different points on one road, and any statistic taken in
 * absolute screen pixels is dominated by that rather than by the road's profile.
 */
private fun rowShapeAboutItsAnchor(label: PlacedLabel): List<Double> =
    label.quads.flatMap { quad ->
        quad.cornersXy.mapIndexed { index, value ->
            value - if (index % 2 == 0) label.anchorPixelX else label.anchorPixelY
        }
    }

/** The largest disagreement between two rows, in output pixels, corner by corner. */
private fun shapeDistance(first: List<Double>, second: List<Double>): Double =
    first.indices.maxOf { index -> abs(first[index] - second[index]) }

private fun distance(fromX: Double, fromY: Double, toX: Double, toY: Double): Double {
    val stepX = toX - fromX
    val stepY = toY - fromY
    return sqrt(stepX * stepX + stepY * stepY)
}

private fun insideViewport(pixelX: Double, pixelY: Double, margin: Double): Boolean =
    pixelX - margin > 0.0 &&
        pixelY - margin > 0.0 &&
        pixelX + margin < PLACEMENT_OUTPUT.width.toDouble() &&
        pixelY + margin < PLACEMENT_OUTPUT.height.toDouble()

/**
 * A west-to-east ramp across [LONG_LINE]'s own span, from sea level at its western end to
 * [RAMP_EASTERN_METRES] at its eastern one.
 *
 * A ramp rather than a summit because a line label's subject is the *shape* of the projected
 * polyline: a summit at one end of a line and a plateau over all of it produce the same picture for
 * any implementation that samples once.
 */
private fun rampMetres(latitude: Double, unwrappedLongitude: Double): Double {
    val span = (LONG_LINE.last().longitude - LONG_LINE.first().longitude)
    val along = (unwrappedLongitude - LONG_LINE.first().longitude) / span
    // Latitude is read so that a lookup handed the two arguments the wrong way round produces a
    // wildly different surface rather than the same one: this ramp is a function of both.
    val northward = (latitude - LONG_LINE.first().latitude) * DEGREES_TO_RAMP_METRES
    return RAMP_EASTERN_METRES * min(max(along, 0.0), 1.0) + northward
}

/**
 * [rampMetres] recentred so that it sinks the western half of [LONG_LINE] exactly as far as it raises
 * the eastern half: `-`[RAMP_AMPLITUDE_METRES] at the line's first point and `+`[RAMP_AMPLITUDE_METRES]
 * at its last.
 *
 * **Negative is not a trick.** Both supported DEM encodings reach well below sea level -- Mapbox's
 * packing bottoms out at -10,000 m -- and a road along a valley floor genuinely sits below the
 * ellipsoid in plenty of the world. What the antisymmetry buys is a picture no single height can
 * imitate: the row's two ends move in opposite screen directions.
 */
private fun tiltedRampMetres(latitude: Double, unwrappedLongitude: Double): Double {
    val span = (LONG_LINE.last().longitude - LONG_LINE.first().longitude)
    val along = min(max((unwrappedLongitude - LONG_LINE.first().longitude) / span, 0.0), 1.0)
    // The latitude term is centred on the line's own mean so that it stays small along the line while
    // still making this a function of both arguments -- a lookup handed them the wrong way round
    // produces a wildly different surface rather than the same one.
    val northward = (latitude - LONG_LINE_MEAN_LATITUDE) * DEGREES_TO_RAMP_METRES
    // The whole of the rise happens across [RAMP_TRANSITION_FRACTION] of the line, centred on its
    // midpoint, which is where a `line-center` row sits: a road that ramps evenly end to end
    // barely tilts under a glyph row covering a sixth of it, and the profile has to put its
    // gradient where the label is or the fixture measures the flat parts.
    val centred = (along - 0.5) / RAMP_TRANSITION_FRACTION
    return RAMP_AMPLITUDE_METRES * min(max(centred, -1.0), 1.0) + northward
}

private val LONG_LINE_MEAN_LATITUDE: Double = LONG_LINE.sumOf { it.latitude } / LONG_LINE.size

/** A second anchor, well south and west of [PLACEMENT_ANCHOR] and sharing no digit with it. */
private val SOUTHERN_ANCHOR: GeographicPosition =
    GeographicPosition(latitude = 48.851274, unwrappedLongitude = 2.301836, altitudeMetres = 0.0)

/**
 * A ridge tall enough to move a label by tens of pixels under the fixture's 52-degree pitch, and
 * short enough to keep both anchors on a 853-by-509 screen -- both of which the cases assert rather
 * than assume.
 *
 * Not a round hundred and not equal to [SOUTHERN_METRES], so a height swapped between two anchors is
 * a different number rather than the same one.
 */
private const val RIDGE_METRES: Double = 274.0

/** The southern anchor's own height. Deliberately unequal to [RIDGE_METRES] and not a multiple of it. */
private const val SOUTHERN_METRES: Double = 93.0

private const val RAMP_EASTERN_METRES: Double = 420.0

/**
 * How far [tiltedRampMetres] sinks one end of the road and raises the other.
 *
 * Large because the glyph row occupies only the middle of the projected run: at this amplitude the
 * row's own two ends sit a couple of hundred metres apart, which is tens of pixels of movement in
 * opposite directions rather than the handful the assertion's floor would let through.
 */
private const val RAMP_AMPLITUDE_METRES: Double = 500.0

/** How much of the line [tiltedRampMetres] spends climbing, centred on its midpoint. */
private const val RAMP_TRANSITION_FRACTION: Double = 0.12

/**
 * Every height a build that sampled [tiltedRampMetres] once could plausibly have come back with --
 * the line's first point, its last, its midpoint, and the quarter points between -- so that "no
 * single height reproduces this row" is asserted against the family rather than against one guess.
 */
private val CONSTANT_HEIGHTS_METRES: List<Double> = listOf(
    -RAMP_AMPLITUDE_METRES,
    -RAMP_AMPLITUDE_METRES / 2.0,
    0.0,
    RAMP_AMPLITUDE_METRES / 2.0,
    RAMP_AMPLITUDE_METRES,
)

/** Steep enough that a transposed (latitude, longitude) pair is a visibly different surface. */
private const val DEGREES_TO_RAMP_METRES: Double = 90_000.0

/**
 * The floor every "it moved" assertion carries, in output pixels. Well above the tolerance below, so
 * a case cannot pass on rounding.
 */
private const val MINIMUM_RIDGE_SHIFT_PIXELS: Double = 8.0

/** [lineGlyphRow]'s own length, restated as a floor so an empty row cannot satisfy the case. */
private const val MINIMUM_LINE_GLYPHS: Int = 6

private const val PIXEL_TOLERANCE: Double = 1.0e-9

/** `ResolvedIconQuad` narrows to `Float`, so the icon's deltas are compared at `Float` precision. */
private const val FLOAT_PIXEL_TOLERANCE: Float = 1.0e-2f

private const val ICON_QUAD_FLOATS: Int = 8

