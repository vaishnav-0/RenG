package com.rohittp.reng.internal.label

import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.rentile.LabelGlyphQuad
import com.rohittp.rentile.LabelPlacement
import com.rohittp.rentile.SymbolAlignment
import com.rohittp.rentile.SymbolOverlap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Point placement, priority and collision.
 *
 * **Every collision case here is asymmetric in the specific way that makes it discriminate**, because
 * the three symmetries this subject offers all produce cases that pass under a wrong implementation:
 *
 *  - two labels at *equal* priority pass under either ordering rule, and under "whichever came
 *    first", so every priority case below gives its two labels different priorities, asserts the
 *    survivor **by identity**, and then runs the same pair with the batch order reversed;
 *  - two boxes overlapping in **one axis only** pass a collision check that reads the other axis and
 *    ignores this one, so the axis case runs all four combinations -- x only, y only, both, neither;
 *  - a label at the viewport centre with no translate is symmetric in every axis, so the shared
 *    anchor is off-centre by different amounts in x and in y and the shared box is four times wider
 *    to the right of the anchor than to the left of it.
 */
class LabelPlacementTest {

    // ---- placement --------------------------------------------------------------------------

    /**
     * The composition: projected anchor, then the label-local cell corners around it, in the corner
     * order and the atlas coordinates [com.rohittp.reng.internal.gl.ResolvedGlyphQuad] documents.
     *
     * The anchor itself is [com.rohittp.reng.internal.projection.projectGeographicPosition]'s answer
     * rather than a number rederived here -- that function has its own suite, and restating its
     * arithmetic would test the restatement. What is derived independently is everything this pass
     * adds: the four corners are written out as four different expressions rather than as one
     * offset, so a swapped width and height, a transposed corner order or a `v` divided by the
     * atlas's width all fail.
     */
    @Test
    fun glyphCellsLandAtTheProjectedAnchorInTheDocumentedCornerOrder() {
        val camera = resolvedPlacementCamera()
        val anchor = projectedAnchor(camera)
        val placed = placeLabels(camera, placementBatch(placementCandidate())).single()

        assertClose(anchor.pixelX, placed.anchorPixelX)
        assertClose(anchor.pixelY, placed.anchorPixelY)

        val quad = placed.quads.single()
        val left = anchor.pixelX + 7.5
        val top = anchor.pixelY - 13.25
        val right = left + 12 * 0.75
        val bottom = top + 20 * 0.75
        assertCorners(
            doubleArrayOf(left, top, right, top, right, bottom, left, bottom),
            quad.cornersXy,
            "screen corners",
        )
        assertCorners(
            doubleArrayOf(
                33.0 / ATLAS_WIDTH, 51.0 / ATLAS_HEIGHT,
                45.0 / ATLAS_WIDTH, 51.0 / ATLAS_HEIGHT,
                45.0 / ATLAS_WIDTH, 71.0 / ATLAS_HEIGHT,
                33.0 / ATLAS_WIDTH, 71.0 / ATLAS_HEIGHT,
            ),
            quad.cornersUv,
            "atlas corners",
            FLOAT_UNIT_TOLERANCE,
        )

        // The cell is 9 pixels wide and 15 tall on screen, so neither axis can stand in for the
        // other and a quad collapsed onto one corner would not get this far.
        assertClose(9.0, (quad.cornersXy[2] - quad.cornersXy[0]).toDouble(), FLOAT_PIXEL_TOLERANCE)
        assertClose(15.0, (quad.cornersXy[5] - quad.cornersXy[3]).toDouble(), FLOAT_PIXEL_TOLERANCE)
    }

    /**
     * `text-translate` under `text-translate-anchor: viewport` is a screen displacement and nothing
     * else, whatever the camera is doing. The fixture camera carries a 37.5 degree bearing precisely
     * so that a translate accidentally routed through the map frame lands somewhere else.
     */
    @Test
    fun aViewportAlignedTranslateMovesTheAnchorInScreenPixels() {
        val camera = resolvedPlacementCamera()
        val anchor = projectedAnchor(camera)
        val placed = placeLabels(
            camera,
            placementBatch(
                placementCandidate(
                    translateX = 13.0,
                    translateY = -29.0,
                    translateAlignment = SymbolAlignment.VIEWPORT,
                ),
            ),
        ).single()

        assertClose(anchor.pixelX + 13.0, placed.anchorPixelX)
        assertClose(anchor.pixelY - 29.0, placed.anchorPixelY)
    }

    /**
     * The same translate under `text-translate-anchor: map`, which is the specification's default and
     * the setting 60 layers across 12 styles rely on.
     *
     * Facing east, map-east is screen-up and map-south is screen-right, so `(13, -29)` -- 13 pixels
     * east, 29 pixels *north* -- must land 29 pixels left and 13 pixels up. The two components are
     * different magnitudes and different signs, so a rotation applied in the wrong direction, or one
     * that swapped the axes, produces a different pair rather than the same one. The bearing-zero
     * half is the boundary the two alignments must agree on, and it is asserted in the same case so
     * that a rotation applied unconditionally cannot hide behind it.
     */
    @Test
    fun aMapAlignedTranslateTurnsWithTheBearingAndAgreesWithTheViewportAtBearingZero() {
        val facingEast = resolvedPlacementCamera(placementCamera(bearing = 90.0))
        val eastAnchor = projectedAnchor(facingEast)
        val turned = placeLabels(
            facingEast,
            placementBatch(
                placementCandidate(
                    translateX = 13.0,
                    translateY = -29.0,
                    translateAlignment = SymbolAlignment.MAP,
                ),
            ),
        ).single()

        assertClose(eastAnchor.pixelX - 29.0, turned.anchorPixelX)
        assertClose(eastAnchor.pixelY - 13.0, turned.anchorPixelY)

        val facingNorth = resolvedPlacementCamera(placementCamera(bearing = 0.0))
        val northAnchor = projectedAnchor(facingNorth)
        val unturned = placeLabels(
            facingNorth,
            placementBatch(
                placementCandidate(
                    translateX = 13.0,
                    translateY = -29.0,
                    translateAlignment = SymbolAlignment.MAP,
                ),
            ),
        ).single()

        assertClose(northAnchor.pixelX + 13.0, unturned.anchorPixelX)
        assertClose(northAnchor.pixelY - 29.0, unturned.anchorPixelY)
    }

    /**
     * `text-rotate` turns the label clockwise about its anchor, and
     * `text-rotation-alignment: map` adds the map's own turn on top of it.
     *
     * A quarter turn is used because it is the one angle whose answer can be written down exactly:
     * a corner 10 pixels to the right of the anchor must end up 10 pixels *below* it, and the box's
     * width and height must have exchanged places. Both facts are false under a rotation of the
     * right magnitude in the wrong direction, which a smaller angle would only shift.
     */
    @Test
    fun textRotateTurnsTheLabelClockwiseAndMapAlignmentAddsTheBearing() {
        val camera = resolvedPlacementCamera()
        val anchor = projectedAnchor(camera)
        val quarterTurn = placeLabels(
            camera,
            placementBatch(
                placementCandidate(
                    textRotationDegrees = 90.0,
                    glyphs = listOf(LabelGlyphQuad(entryIndex = 0, x = 10.0, y = 0.0, scale = 1.0)),
                ),
            ),
        ).single()

        // Top-left at label-local (10, 0) and top-right at (22, 0), a quarter turn clockwise.
        assertClose(anchor.pixelX, quarterTurn.quads.single().cornersXy[0].toDouble(), FLOAT_PIXEL_TOLERANCE)
        assertClose(anchor.pixelY + 10.0, quarterTurn.quads.single().cornersXy[1].toDouble(), FLOAT_PIXEL_TOLERANCE)
        assertClose(anchor.pixelX, quarterTurn.quads.single().cornersXy[2].toDouble(), FLOAT_PIXEL_TOLERANCE)
        assertClose(anchor.pixelY + 22.0, quarterTurn.quads.single().cornersXy[3].toDouble(), FLOAT_PIXEL_TOLERANCE)

        // The box (-30, -11) to (70, 9) turns into x in [-9, 11] and y in [-30, 70]: a hundred-wide,
        // twenty-tall box became twenty wide and a hundred tall, which a half turn would not do.
        assertClose(anchor.pixelX - 9.0, quarterTurn.collisionBox.left)
        assertClose(anchor.pixelX + 11.0, quarterTurn.collisionBox.right)
        assertClose(anchor.pixelY - 30.0, quarterTurn.collisionBox.top)
        assertClose(anchor.pixelY + 70.0, quarterTurn.collisionBox.bottom)

        // Facing east with no `text-rotate` at all: the map's own quarter turn is anticlockwise on
        // screen, so the same corner ends up 10 pixels ABOVE the anchor rather than below it. The
        // viewport-aligned control in the same camera is what makes that a statement about the
        // alignment rather than about the bearing.
        val facingEast = resolvedPlacementCamera(placementCamera(bearing = 90.0))
        val eastAnchor = projectedAnchor(facingEast)
        val glyph = listOf(LabelGlyphQuad(entryIndex = 0, x = 10.0, y = 0.0, scale = 1.0))
        val withMap = placeLabels(
            facingEast,
            placementBatch(
                placementCandidate(rotationAlignment = SymbolAlignment.MAP, glyphs = glyph),
            ),
        ).single()
        val withViewport = placeLabels(
            facingEast,
            placementBatch(
                placementCandidate(rotationAlignment = SymbolAlignment.VIEWPORT, glyphs = glyph),
            ),
        ).single()

        assertClose(eastAnchor.pixelX, withMap.quads.single().cornersXy[0].toDouble(), FLOAT_PIXEL_TOLERANCE)
        assertClose(eastAnchor.pixelY - 10.0, withMap.quads.single().cornersXy[1].toDouble(), FLOAT_PIXEL_TOLERANCE)
        assertClose(eastAnchor.pixelX + 10.0, withViewport.quads.single().cornersXy[0].toDouble(), FLOAT_PIXEL_TOLERANCE)
        assertClose(eastAnchor.pixelY, withViewport.quads.single().cornersXy[1].toDouble(), FLOAT_PIXEL_TOLERANCE)
    }

    /**
     * The engine's paint, straight and per candidate. All eight colour bytes differ from each other,
     * so a channel read in the wrong order -- `0xAABBGGRR`, or RGBA where the engine packs ARGB --
     * produces different numbers rather than the same ones.
     */
    @Test
    fun paintCarriesStraightRgbaAndTheScreenPixelScalarsPerCandidate() {
        val camera = resolvedPlacementCamera()
        val placed = placeLabels(
            camera,
            placementBatch(
                placementCandidate(opacity = 0.625, haloWidth = 1.75, haloBlur = 0.5),
            ),
        ).single()

        val paint = placed.quads.single().paint
        assertCorners(
            doubleArrayOf(26.0 / 255.0, 43.0 / 255.0, 60.0 / 255.0, 76.0 / 255.0),
            paint.textColour,
            "text colour",
            FLOAT_UNIT_TOLERANCE,
        )
        assertCorners(
            doubleArrayOf(241.0 / 255.0, 210.0 / 255.0, 195.0 / 255.0, 224.0 / 255.0),
            paint.haloColour,
            "halo colour",
            FLOAT_UNIT_TOLERANCE,
        )
        assertEquals(0.625f, paint.opacity)
        assertEquals(1.75f, paint.haloWidthPixels)
        assertEquals(0.5f, paint.haloBlurPixels)
        assertEquals(0.75f, paint.scale)
    }

    // ---- priority ---------------------------------------------------------------------------

    /**
     * The higher `symbol-sort-key` wins, and the assertion names the winner.
     *
     * Two labels at equal priority pass under either ordering rule and under "whichever the engine
     * listed first", which is why the keys differ and why the identical pair is then run with the
     * batch order reversed: a pass that resolved by list position would place a different label the
     * second time.
     */
    @Test
    fun theHigherSortKeyWinsWhicheverOrderTheBatchListsThem() {
        val camera = resolvedPlacementCamera()
        val quiet = placementCandidate(sortKey = 1.0)
        val loud = placementCandidate(sortKey = 7.0, top = -10.0, bottom = 10.0)

        val loudLast = placeLabels(camera, placementBatch(quiet, loud)).single()
        assertEquals(1, loudLast.candidateIndex, "the sort key of 7 wins from second in the list")

        val loudFirst = placeLabels(camera, placementBatch(loud, quiet)).single()
        assertEquals(0, loudFirst.candidateIndex, "and still wins from first in the list")
    }

    /**
     * Layer order is the tiebreak, not the primary key: with the sort keys equal, the later-declared
     * layer wins, and it wins from either position in the batch.
     */
    @Test
    fun theLaterLayerBreaksAnEqualSortKeyWhicheverOrderTheBatchListsThem() {
        val camera = resolvedPlacementCamera()
        val early = placementCandidate(sortKey = 3.0, layerStyleIndex = 0)
        val late = placementCandidate(sortKey = 3.0, layerStyleIndex = 1, top = -10.0, bottom = 10.0)

        assertEquals(1, placeLabels(camera, placementBatch(early, late)).single().candidateIndex)
        assertEquals(0, placeLabels(camera, placementBatch(late, early)).single().candidateIndex)
    }

    /**
     * And the ranking between the two keys: a smaller sort key does not win on a later layer.
     *
     * This is the case that separates "sort key first, layer order as tiebreak" from the plausible
     * alternative of ranking layer order first, and it is the only one that does -- every other
     * priority case here passes under both readings.
     */
    @Test
    fun aLargerSortKeyOnAnEarlierLayerBeatsASmallerOneOnALaterLayer() {
        val camera = resolvedPlacementCamera()
        val loudEarlyLayer = placementCandidate(sortKey = 7.0, layerStyleIndex = 0)
        val quietLateLayer =
            placementCandidate(sortKey = 1.0, layerStyleIndex = 1, top = -10.0, bottom = 10.0)

        assertEquals(
            0,
            placeLabels(camera, placementBatch(loudEarlyLayer, quietLateLayer)).single().candidateIndex,
        )
        assertEquals(
            1,
            placeLabels(camera, placementBatch(quietLateLayer, loudEarlyLayer)).single().candidateIndex,
        )
    }

    /**
     * Survivors are handed over lowest priority first, so the highest-priority label paints last and
     * therefore on top wherever `always` or `text-ignore-placement` let two labels share pixels. The
     * pipeline draws a batch in the order it is given and sorts nothing itself.
     */
    @Test
    fun survivorsAreHandedOverLowestPriorityFirst() {
        val camera = resolvedPlacementCamera()
        val placed = placeLabels(
            camera,
            placementBatch(
                placementCandidate(sortKey = 5.0, translateY = 0.0),
                placementCandidate(sortKey = 9.0, translateY = 40.0),
                placementCandidate(sortKey = 1.0, translateY = 80.0),
            ),
        )

        assertEquals(listOf(2, 0, 1), placed.map { it.candidateIndex })
    }

    // ---- collision --------------------------------------------------------------------------

    /**
     * A rectangle intersection needs both axes, and this is the case a one-axis check passes.
     *
     * Four pairs against one fixed box: overlapping in x alone, in y alone, in both, and in neither.
     * A check that reads only x drops the y-only pair as well as the both pair; a check that reads
     * only y drops the x-only pair. Only a check reading both leaves exactly one pair with a single
     * survivor.
     */
    @Test
    fun boxesMustOverlapInBothAxesToCollide() {
        val camera = resolvedPlacementCamera()
        val anchored = placementCandidate(sortKey = 9.0)

        for ((description, other, expected) in listOf(
            Triple("x only", placementCandidate(left = 0.0, top = 20.0, right = 40.0, bottom = 35.0), 2),
            Triple("y only", placementCandidate(left = 100.0, top = -5.0, right = 140.0, bottom = 5.0), 2),
            Triple("both", placementCandidate(left = 60.0, top = 5.0, right = 140.0, bottom = 30.0), 1),
            Triple("neither", placementCandidate(left = 100.0, top = 20.0, right = 140.0, bottom = 35.0), 2),
        )) {
            val placed = placeLabels(camera, placementBatch(anchored, other))
            assertEquals(expected, placed.size, "overlapping in $description")
            assertTrue(placed.any { it.candidateIndex == 0 }, "the higher priority survives $description")
        }
    }

    /**
     * `text-overlap: always` places regardless of what is already there, and still claims its own
     * space against everything after it. The two halves are separate rulings and each has its own
     * assertion: the first would pass if `always` merely skipped the query, and the second would
     * pass if it merely skipped the insert.
     */
    @Test
    fun alwaysOverlapPlacesAnywayAndStillClaimsItsSpace() {
        val camera = resolvedPlacementCamera()
        val overlapping = placeLabels(
            camera,
            placementBatch(
                placementCandidate(sortKey = 9.0),
                placementCandidate(sortKey = 1.0, overlap = SymbolOverlap.ALWAYS),
            ),
        )
        assertEquals(setOf(0, 1), overlapping.map { it.candidateIndex }.toSet())

        val blocked = placeLabels(
            camera,
            placementBatch(
                placementCandidate(sortKey = 9.0, overlap = SymbolOverlap.ALWAYS),
                placementCandidate(sortKey = 1.0),
            ),
        )
        assertEquals(listOf(0), blocked.map { it.candidateIndex }, "an always label still blocks")
    }

    /**
     * `text-ignore-placement` claims no space, and the control is what makes the case mean anything:
     * the same pair with the flag off loses the lower-priority label.
     */
    @Test
    fun ignorePlacementClaimsNoSpaceWhileTheSamePairWithoutItCollides() {
        val camera = resolvedPlacementCamera()
        val invisible = placeLabels(
            camera,
            placementBatch(
                placementCandidate(sortKey = 9.0, ignorePlacement = true),
                placementCandidate(sortKey = 1.0),
            ),
        )
        assertEquals(setOf(0, 1), invisible.map { it.candidateIndex }.toSet())

        val visible = placeLabels(
            camera,
            placementBatch(
                placementCandidate(sortKey = 9.0, ignorePlacement = false),
                placementCandidate(sortKey = 1.0),
            ),
        )
        assertEquals(listOf(0), visible.map { it.candidateIndex })
    }

    /**
     * `cooperative` is resolved as `never` -- zero corpus occurrences, and a negotiation this cycle
     * cannot perform. The `always` arm in the same case is the control: if the `when` collapsed every
     * non-`never` value the same way, the cooperative label and the always label would both survive.
     */
    @Test
    fun cooperativeOverlapIsResolvedAsNeverAndNotAsAlways() {
        val camera = resolvedPlacementCamera()
        val cooperative = placeLabels(
            camera,
            placementBatch(
                placementCandidate(sortKey = 9.0),
                placementCandidate(sortKey = 1.0, overlap = SymbolOverlap.COOPERATIVE),
            ),
        )
        assertEquals(listOf(0), cooperative.map { it.candidateIndex })

        val always = placeLabels(
            camera,
            placementBatch(
                placementCandidate(sortKey = 9.0),
                placementCandidate(sortKey = 1.0, overlap = SymbolOverlap.ALWAYS),
            ),
        )
        assertEquals(setOf(0, 1), always.map { it.candidateIndex }.toSet())
    }

    /**
     * The engine's `boundingBox` already carries `text-padding` on all four sides, so this pass must
     * not add it again.
     *
     * Two boxes six pixels apart, each declaring five pixels of padding. Adding the padding a second
     * time closes a ten-pixel gap over a six-pixel separation and drops one label; not adding it
     * keeps both. The second half is the control that stops the case passing for the trivial reason
     * that nothing ever collides: moving them two pixels closer than the box says does drop one.
     */
    @Test
    fun paddingIsNotAppliedASecondTime() {
        val camera = resolvedPlacementCamera()
        val separated = placeLabels(
            camera,
            placementBatch(
                placementCandidate(sortKey = 9.0, left = -30.0, right = 70.0, padding = 5.0),
                placementCandidate(sortKey = 1.0, left = 76.0, right = 140.0, padding = 5.0),
            ),
        )
        assertEquals(2, separated.size, "a six pixel gap is a gap; the padding is already in the box")

        val touching = placeLabels(
            camera,
            placementBatch(
                placementCandidate(sortKey = 9.0, left = -30.0, right = 70.0, padding = 5.0),
                placementCandidate(sortKey = 1.0, left = 68.0, right = 140.0, padding = 5.0),
            ),
        )
        assertEquals(listOf(0), touching.map { it.candidateIndex }, "and a two pixel overlap collides")
    }

    /**
     * The viewport grid and a brute-force scan agree, over four hundred labels spread across the
     * screen by their translates.
     *
     * The grid exists because E5 ships no ceiling on candidate count, so the pass must not be
     * quadratic -- and a spatial index is exactly the kind of optimisation whose bugs are invisible
     * in a two-label fixture. The reference implementation below shares no code with the one under
     * test beyond the box arithmetic the fixture itself defines, and the case is a lower bound as
     * well as an upper one: it asserts that some labels actually lost, so an index that found no
     * collisions at all would fail rather than agree with a reference that also found none.
     */
    @Test
    fun theViewportGridAgreesWithABruteForceScan() {
        val camera = resolvedPlacementCamera()
        val anchor = projectedAnchor(camera)
        var seed = 0x5eed_1234L
        fun next(bound: Int): Int {
            seed = (seed * 6364136223846793005L + 1442695040888963407L) ushr 1
            return (seed % bound).toInt()
        }

        val candidates = List(400) {
            placementCandidate(
                sortKey = next(97).toDouble(),
                left = 0.0,
                top = 0.0,
                right = (11 + next(59)).toDouble(),
                bottom = (5 + next(23)).toDouble(),
                translateX = (next(900) - 500).toDouble(),
                translateY = (next(600) - 300).toDouble(),
            )
        }
        val batch = placementBatch(*candidates.toTypedArray())
        val viewport = LabelScreenBox(0.0, 0.0, 853.0, 509.0)

        val reference = ArrayList<Int>()
        val occupied = ArrayList<LabelScreenBox>()
        for (index in candidates.indices.sortedWith(compareByDescending { candidates[it].sortKey })) {
            val candidate = candidates[index]
            val box = LabelScreenBox(
                left = anchor.pixelX + candidate.translateX + candidate.boundingBox.left,
                top = anchor.pixelY + candidate.translateY + candidate.boundingBox.top,
                right = anchor.pixelX + candidate.translateX + candidate.boundingBox.right,
                bottom = anchor.pixelY + candidate.translateY + candidate.boundingBox.bottom,
            )
            if (!box.intersects(viewport)) continue
            if (occupied.any { it.intersects(box) }) continue
            occupied += box
            reference += index
        }

        assertTrue(reference.isNotEmpty() && reference.size < candidates.size, "the fixture must contend")
        assertEquals(reference.reversed(), placeLabels(camera, batch).map { it.candidateIndex })
    }

    // ---- what has no pixel ------------------------------------------------------------------

    /**
     * An anchor with no pixel drops its own label and nothing else. The projection is total for
     * exactly this reason, and the surviving label in every case is what proves the frame lived.
     */
    @Test
    fun anchorsWithNoPixelDropOneLabelRatherThanFailingTheFrame() {
        val camera = resolvedPlacementCamera()
        val placed = placeLabels(
            camera,
            placementBatch(
                placementCandidate(sortKey = 9.0, position = PLACEMENT_ANCHOR_BEHIND),
                placementCandidate(
                    sortKey = 8.0,
                    position = GeographicPosition(91.0, 2.309995, 0.0),
                ),
                placementCandidate(sortKey = 7.0),
            ),
        )

        assertEquals(listOf(2), placed.map { it.candidateIndex })
    }

    /**
     * A label whose box misses the viewport entirely is dropped; one that straddles an edge is not.
     * Without the second half the case would pass against a pass that placed nothing at all.
     */
    @Test
    fun labelsEntirelyOutsideTheViewportAreDroppedAndStraddlingOnesAreNot() {
        val camera = resolvedPlacementCamera()
        val placed = placeLabels(
            camera,
            placementBatch(
                placementCandidate(sortKey = 9.0, left = 1000.0, right = 1100.0),
                placementCandidate(sortKey = 1.0, left = -700.0, right = -620.0),
            ),
        )

        assertEquals(listOf(1), placed.map { it.candidateIndex })
    }

    /**
     * Line-placed candidates are passed over untouched. Rentile lays every placement mode out as one
     * horizontal row, so placing a `LINE` candidate here would draw a road name as a horizontal block
     * at its anchor -- a wrong picture rather than a missing one. Task 11 owns them.
     */
    @Test
    fun lineCandidatesAreLeftForLinePlacement() {
        val camera = resolvedPlacementCamera()
        val placed = placeLabels(
            camera,
            placementBatch(
                placementCandidate(sortKey = 9.0, placement = LabelPlacement.LINE),
                placementCandidate(sortKey = 8.0, placement = LabelPlacement.LINE_CENTER),
                placementCandidate(sortKey = 7.0, placement = LabelPlacement.POINT),
            ),
        )

        assertEquals(listOf(2), placed.map { it.candidateIndex })
    }

    /**
     * Numbers that cannot be drawn drop their label instead of reaching the collision index.
     *
     * A NaN box compares false against every bound, so a label carrying one would survive the
     * viewport cull, collide with nothing, and then be handed to the GPU as NaN vertices. A NaN sort
     * key orders consistently and means nothing. Each case here keeps one good label so that the
     * assertion is "one dropped", not "everything dropped".
     */
    @Test
    fun nonDrawableNumbersDropOneLabelRatherThanPoisoningTheIndex() {
        val camera = resolvedPlacementCamera()
        for ((description, broken) in listOf(
            "translate" to placementCandidate(sortKey = 9.0, translateX = Double.NaN),
            "sort key" to placementCandidate(sortKey = Double.NaN),
            "box" to placementCandidate(sortKey = 9.0, right = Double.POSITIVE_INFINITY),
            "opacity" to placementCandidate(sortKey = 9.0, opacity = Double.NaN),
            "glyph scale" to placementCandidate(
                sortKey = 9.0,
                glyphs = listOf(LabelGlyphQuad(entryIndex = 0, x = 1.0, y = 2.0, scale = 0.0)),
            ),
            "glyph entry" to placementCandidate(
                sortKey = 9.0,
                glyphs = listOf(LabelGlyphQuad(entryIndex = 4, x = 1.0, y = 2.0, scale = 1.0)),
            ),
        )) {
            val placed = placeLabels(
                camera,
                placementBatch(broken, placementCandidate(sortKey = -9.0, translateY = 120.0)),
            )
            assertEquals(listOf(1), placed.map { it.candidateIndex }, "a broken $description")
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

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
        /**
         * A quad corner is a `Float`, and a pixel near 700 carries about 6e-5 of representation, so a
         * difference between two of them carries twice that. Nothing asserted at this tolerance is a
         * near miss: every wrong answer below is out by whole pixels or by a sign.
         */
        const val FLOAT_PIXEL_TOLERANCE: Double = 1e-3

        /** An atlas coordinate is a `Float` in `[0, 1]`, where the representation is far finer. */
        const val FLOAT_UNIT_TOLERANCE: Double = 1e-7

        /** Anchors and collision boxes stay in `Double`; a quarter turn goes through `cos(PI / 2)`. */
        const val DOUBLE_TOLERANCE: Double = 1e-9
    }
}
