package com.rohittp.reng.internal.label

import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.rentile.LabelCandidate
import com.rohittp.rentile.LabelGlyphQuad
import com.rohittp.rentile.LabelLinePoint
import com.rohittp.rentile.LabelPlacement
import com.rohittp.rentile.SymbolAlignment
import com.rohittp.rentile.SymbolOverlap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Line placement: the walk, the distribution, the bend ceiling and `keepUpright`.
 *
 * **Every symmetry this subject offers is closed in the fixture rather than in the case**, because
 * each of them makes a broken implementation produce the right picture:
 *
 *  - on a **straight** line every glyph shares one tangent, `keepUpright` never fires and the bend
 *    ceiling never fires, so a tangent computed from anything at all -- the engine's north-up hint,
 *    the first segment, a constant -- is correct. Every geometric case below runs on a line that
 *    turns, and [theBendCeilingRefusesAContortedInstance] runs on one that turns by 88 degrees;
 *  - on a line running **left to right** `keepUpright` never fires.
 *    [keepUprightReadsARightToLeftLineBackwards] runs on one going the other way, and runs the same
 *    fixture with the property off;
 *  - a **symmetric** arc cannot separate a tangent from its sign flip, so both bent fixtures turn
 *    once, by different amounts on either side, with legs of different lengths;
 *  - a `line-center` on **equal** segments lands on a vertex, where interpolating and snapping
 *    agree. [lineCentrePlacesOneInstanceStrictlyInsideASegment] asserts the centre is nowhere near
 *    one before it asserts anything about the label.
 *
 * The measured screen geometry of every fixture is recorded beside the case that depends on it, so
 * that a fixture edit that quietly moves a number shows up as a failing case rather than as a
 * still-passing one.
 */
class LineLabelPlacementTest {

    // ---- distribution along the curve --------------------------------------------------------

    /**
     * Every glyph sits **on** the projected polyline, at its own arc length, turned to that point's
     * own tangent -- and the label genuinely bends, which is the assertion a straight fixture cannot
     * make.
     *
     * `LONG_LINE` projects to four pixels 175.754 apart in total, with vertices at 45.475 and
     * 102.481 and a 4.766-degree turn at the second. A `line-center` anchor lands at 87.877, so the
     * six glyph centres fall at 64.4, 74.9, 87.4, 98.9, 112.4 and 123.4 -- the first four before the
     * vertex and the last two after it. That split is what makes the last assertion possible.
     */
    @Test
    fun glyphsSitOnTheProjectedLineAtTheirOwnArcLengthAndTurnWithIt() {
        val camera = resolvedPlacementCamera()
        val run = screenRunOf(camera, LONG_LINE)
        val placed = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(placement = LabelPlacement.LINE_CENTER),
            candidateIndex = 0,
        ).single()

        val anchorAlongLine = run.nearestOn(placed.anchorPixelX, placed.anchorPixelY)
        assertClose(0.0, anchorAlongLine.offLine, "the anchor is on the line")
        assertClose(run.length / 2.0, anchorAlongLine.alongLine, "the anchor is at the centre")

        assertEquals(LINE_GLYPH_LOCAL_X.size, placed.quads.size)
        val axes = ArrayList<Pair<Double, Double>>()
        for (index in LINE_GLYPH_LOCAL_X.indices) {
            val frame = recoverGlyphFrame(placed.quads[index], LINE_GLYPH_CELL_WIDTH, LINE_GLYPH_MID_LOCAL_Y)
            val nearest = run.nearestOn(frame.originX, frame.originY)
            val centreLocalX = LINE_GLYPH_LOCAL_X[index] + LINE_GLYPH_CELL_WIDTH / 2.0

            assertClose(0.0, nearest.offLine, "glyph $index sits on the line")
            assertClose(
                run.length / 2.0 + centreLocalX,
                nearest.alongLine,
                "glyph $index is at its own arc length",
            )

            // The frame is a rotation and nothing else: a unit axis, so no glyph is stretched or
            // mirrored by being placed on a curve.
            assertClose(
                1.0,
                frame.axisX * frame.axisX + frame.axisY * frame.axisY,
                "glyph $index has a unit axis",
            )
            axes += frame.axisX to frame.axisY
        }

        // The bend itself. Glyphs 0..3 share the second segment and glyphs 4..5 the third, so a row
        // that came out rigid -- one tangent for the whole label, however it was derived -- fails
        // here and passes everything above it.
        assertTrue(
            abs(axes[0].first - axes[5].first) > BENT_AXIS_SEPARATION,
            "the label bends: ${axes[0]} against ${axes[5]}",
        )
        assertClose(axes[0].first, axes[3].first, "glyphs 0 and 3 share a segment", SHARED_AXIS_TOLERANCE)
        assertClose(axes[4].first, axes[5].first, "glyphs 4 and 5 share a segment", SHARED_AXIS_TOLERANCE)
    }

    /**
     * The `line-center` anchor is an interpolation along a segment, not a snap to the nearest vertex.
     *
     * `LONG_LINE`'s centre at 87.877 sits 42.4 past one vertex and 14.6 before the next, which the
     * first assertion pins: on a line of equal segments the centre would land exactly on a vertex,
     * and interpolating and snapping would agree there.
     */
    @Test
    fun lineCentrePlacesOneInstanceStrictlyInsideASegment() {
        val camera = resolvedPlacementCamera()
        val run = screenRunOf(camera, LONG_LINE)
        val centre = run.length / 2.0
        for (vertex in run.cumulative) {
            assertTrue(
                abs(centre - vertex) > VERTEX_CLEARANCE_PIXELS,
                "the fixture's centre $centre is too close to the vertex at $vertex",
            )
        }

        val placed = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(placement = LabelPlacement.LINE_CENTER),
            candidateIndex = 0,
        )

        assertEquals(1, placed.size)
        assertClose(centre, run.nearestOn(placed[0].anchorPixelX, placed[0].anchorPixelY).alongLine)
    }

    // ---- the walk ----------------------------------------------------------------------------

    /**
     * `line` placement repeats the label every `symbol-spacing` pixels of arc, from half a spacing
     * in, and refuses the repeats that would hang off either end.
     *
     * On `LONG_LINE`'s 175.754 pixels with the shared row's extent of `[-28, +40]`, that is one
     * instance at 120, two at 60 and four at 30 -- the ones at 15 and 165 losing their place to the
     * ends. Halving the spacing at least doubling the count is the property; the exact counts are
     * what a walk that started at the wrong offset or stepped by the wrong amount would break.
     */
    @Test
    fun lineRepeatsEverySpacingAndRefusesTheRepeatsThatRunOffTheEnd() {
        val camera = resolvedPlacementCamera()
        val run = screenRunOf(camera, LONG_LINE)

        for ((spacing, expectedAnchors) in listOf(
            120.0 to listOf(60.0),
            60.0 to listOf(30.0, 90.0),
            30.0 to listOf(45.0, 75.0, 105.0, 135.0),
        )) {
            val placed = layOutLineLabels(
                camera,
                PLACEMENT_ATLAS,
                lineCandidate(symbolSpacing = spacing),
                candidateIndex = 0,
            )
            assertEquals(
                expectedAnchors.size,
                placed.size,
                "instances at a spacing of $spacing",
            )
            for (index in expectedAnchors.indices) {
                val nearest = run.nearestOn(placed[index].anchorPixelX, placed[index].anchorPixelY)
                assertClose(0.0, nearest.offLine, "anchor $index at a spacing of $spacing is on the line")
                assertClose(
                    expectedAnchors[index],
                    nearest.alongLine,
                    "anchor $index at a spacing of $spacing",
                )
            }
        }
    }

    /**
     * A label longer than the line it is placed on is not placed at all, rather than crushed onto
     * the line's last vertex.
     *
     * `STUB_LINE` is 2.278 screen pixels long against a 68-pixel row. The control is the same
     * candidate on `LONG_LINE`, so the only thing that differs between placed and not placed is the
     * line.
     */
    @Test
    fun aLabelLongerThanItsLineIsNotPlaced() {
        val camera = resolvedPlacementCamera()
        for (placement in listOf(LabelPlacement.LINE, LabelPlacement.LINE_CENTER)) {
            assertEquals(
                emptyList(),
                layOutLineLabels(
                    camera,
                    PLACEMENT_ATLAS,
                    lineCandidate(line = STUB_LINE, placement = placement),
                    candidateIndex = 0,
                ),
                "$placement on a stub",
            )
            assertEquals(
                1,
                layOutLineLabels(
                    camera,
                    PLACEMENT_ATLAS,
                    lineCandidate(placement = placement, symbolSpacing = 250.0),
                    candidateIndex = 0,
                ).size,
                "$placement on a line long enough",
            )
        }
    }

    /**
     * A line whose middle vertex is behind the camera becomes two runs, and no label spans the gap.
     *
     * The fixture is `LONG_LINE` with one point behind the camera plane spliced into the middle, so
     * the two are the *same four pixels* and differ only in whether the gap exists. Stitching the
     * runs into one 118.748-pixel polyline would put anchors at 15, 45 and 75 of that stitched
     * parameterisation; keeping them apart puts them at 15 on the first run and 15 and 45 on the
     * second. The control asserts the two fixtures really do disagree, which is what makes the
     * expected anchors below evidence of anything.
     */
    @Test
    fun aLineBrokenBehindTheCameraBecomesTwoRunsAndNoLabelSpansTheGap() {
        val camera = resolvedPlacementCamera()
        val runs = screenRunsOf(camera, BROKEN_LINE)
        assertEquals(2, runs.size, "the fixture breaks into two runs")

        val broken = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(line = BROKEN_LINE, symbolSpacing = 30.0, glyphs = shortLineGlyphRow()),
            candidateIndex = 0,
        )
        val whole = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(line = LONG_LINE, symbolSpacing = 30.0, glyphs = shortLineGlyphRow()),
            candidateIndex = 0,
        )
        assertTrue(
            broken.size != whole.size,
            "the broken and whole lines must not agree, or this case proves nothing",
        )

        assertEquals(3, broken.size)
        val expected = listOf(0 to 15.0, 1 to 15.0, 1 to 45.0)
        for (index in expected.indices) {
            val (runIndex, alongLine) = expected[index]
            val nearest = runs[runIndex].nearestOn(broken[index].anchorPixelX, broken[index].anchorPixelY)
            assertClose(0.0, nearest.offLine, "anchor $index is on run $runIndex")
            assertClose(alongLine, nearest.alongLine, "anchor $index along run $runIndex")
        }
    }

    /**
     * `line-center` takes the centre of the longest run, not the centre of a line measured across a
     * gap that has no screen length.
     *
     * `BROKEN_LINE`'s runs are 45.475 and 73.273 pixels, so the answer is 36.636 along the second.
     * The stitched reading -- 59.374 of 118.748 -- would land 22.7 pixels away, well outside the
     * tolerance, and is asserted against directly.
     */
    @Test
    fun lineCentreOnABrokenLineTakesTheLongestRun() {
        val camera = resolvedPlacementCamera()
        val runs = screenRunsOf(camera, BROKEN_LINE)
        val longest = runs.maxBy { it.length }
        assertTrue(runs[0].length < runs[1].length, "the fixture's second run is the longer one")

        val placed = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(
                line = BROKEN_LINE,
                placement = LabelPlacement.LINE_CENTER,
                glyphs = shortLineGlyphRow(),
            ),
            candidateIndex = 0,
        ).single()

        val nearest = longest.nearestOn(placed.anchorPixelX, placed.anchorPixelY)
        assertClose(0.0, nearest.offLine, "the anchor is on the longest run")
        assertClose(longest.length / 2.0, nearest.alongLine, "the anchor halves the longest run")

        val stitchedCentre = (runs[0].length + runs[1].length) / 2.0 - runs[0].length
        assertTrue(
            abs(stitchedCentre - nearest.alongLine) > VERTEX_CLEARANCE_PIXELS,
            "a centre measured across the gap would have landed at $stitchedCentre",
        )
    }

    /**
     * The walk is bounded, because the thing it walks is not.
     *
     * `NEAR_PLANE_LINE`'s far vertex sits 1.05 logical pixels in front of the near plane and lands
     * about 390,000 output pixels away -- an ordinary road under a pitched camera, not a contrived
     * one. At the one-pixel spacing floor that is 390,000 anchors from a single two-point line, in a
     * function that runs inside `prepare()`. The last assertion is what stops the bound from being
     * mistaken for a placement policy: at a real `symbol-spacing` the same run never reaches it.
     */
    @Test
    fun theWalkIsBoundedWhenAVertexSitsAgainstTheNearPlane() {
        val camera = resolvedPlacementCamera()
        val run = screenRunOf(camera, NEAR_PLANE_LINE)
        assertTrue(run.length > 100_000.0, "the fixture's run is only ${run.length} pixels")
        assertTrue(
            run.length / 1.0 > MAXIMUM_ANCHORS_PER_RUN,
            "the fixture must ask for more anchors than the bound allows",
        )

        val dense = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(line = NEAR_PLANE_LINE, symbolSpacing = 1.0, glyphs = shortLineGlyphRow()),
            candidateIndex = 0,
        )
        assertTrue(dense.isNotEmpty(), "the bound refuses nothing outright")
        assertTrue(dense.size <= MAXIMUM_ANCHORS_PER_RUN, "but it stops at ${dense.size}")

        val sparse = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(line = NEAR_PLANE_LINE, symbolSpacing = 250.0, glyphs = shortLineGlyphRow()),
            candidateIndex = 0,
        )
        assertTrue(
            sparse.size < MAXIMUM_ANCHORS_PER_RUN,
            "a real spacing does not reach the bound: ${sparse.size}",
        )
    }

    // ---- the bend ceiling --------------------------------------------------------------------

    /**
     * `text-max-angle` refuses the instance whose glyph-to-glyph turn exceeds it, and refuses
     * nothing else.
     *
     * `BENT_LINE` turns by 88.258 degrees at its only vertex, and its `line-center` anchor at 65.184
     * puts glyphs 1 and 2 on opposite sides of that vertex. The 88-against-89 pair is the whole
     * assertion: a tangent computed any other way moves that measured turn, and one of the two
     * verdicts flips. The straight-enough control on `LONG_LINE`, whose largest glyph-to-glyph turn
     * is 4.766 degrees, is what stops a ceiling that refuses everything from passing.
     */
    @Test
    fun theBendCeilingRefusesAContortedInstance() {
        val camera = resolvedPlacementCamera()

        fun placedOn(line: List<LabelLinePoint>, maxAngleDegrees: Double): Int = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(
                line = line,
                placement = LabelPlacement.LINE_CENTER,
                maxAngleDegrees = maxAngleDegrees,
            ),
            candidateIndex = 0,
        ).size

        assertEquals(0, placedOn(BENT_LINE, 45.0), "the default ceiling refuses an 88-degree bend")
        assertEquals(0, placedOn(BENT_LINE, 88.0), "a ceiling just under the bend refuses it")
        assertEquals(1, placedOn(BENT_LINE, 89.0), "a ceiling just over the bend admits it")
        assertEquals(1, placedOn(LONG_LINE, 45.0), "a 4.8-degree bend is not refused at 45 degrees")
        assertEquals(0, placedOn(LONG_LINE, 4.0), "and is refused at 4")
    }

    /**
     * The ceiling measures the shape of the ink, not the order of the array.
     *
     * A label the engine wrapped onto two rows restarts at its own left edge for the second row, so
     * in glyph order one pair jumps from the label's right end back to its left. On `LONG_LINE`'s
     * `line-center` anchor a `WIDE_LINE_GLYPH_LOCAL_X` row spans both vertices: neighbouring glyphs
     * turn by at most 12.809 degrees, while the two ends of the label differ by 17.574. A ceiling of
     * 15 therefore separates the two readings -- and 12 refuses both, which is what stops "15 admits
     * everything" from being the explanation.
     */
    @Test
    fun theBendCeilingMeasuresTheShapeOfTheInkRatherThanTheOrderOfTheArray() {
        val camera = resolvedPlacementCamera()

        fun placedWith(glyphs: List<LabelGlyphQuad>, maxAngleDegrees: Double): Int = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(
                placement = LabelPlacement.LINE_CENTER,
                maxAngleDegrees = maxAngleDegrees,
                glyphs = glyphs,
            ),
            candidateIndex = 0,
        ).size

        assertEquals(1, placedWith(wideLineGlyphRow(), 15.0), "one row spanning both vertices")
        assertEquals(1, placedWith(twoRowLineGlyphs(), 15.0), "the same ink wrapped onto two rows")
        assertEquals(0, placedWith(wideLineGlyphRow(), 12.0), "and 12 degrees refuses one row")
        assertEquals(0, placedWith(twoRowLineGlyphs(), 12.0), "and refuses two")
    }

    // ---- keepUpright -------------------------------------------------------------------------

    /**
     * `text-keep-upright` reads a right-to-left line backwards so its text still reads left to
     * right, and turning the property off draws it as the line runs.
     *
     * `BACKWARD_LINE` is `LONG_LINE`'s four pixels in reverse, so its screen tangent points leftward
     * throughout -- the first assertion pins that, because on a left-to-right line this case would
     * pass with `keepUpright` doing nothing at all. The anchor is the same pixel under both
     * settings: the property changes the reading direction, not where the label sits.
     */
    @Test
    fun keepUprightReadsARightToLeftLineBackwards() {
        val camera = resolvedPlacementCamera()
        val run = screenRunOf(camera, BACKWARD_LINE)
        assertTrue(run.lastX < run.firstX, "the fixture must run right to left")

        val upright = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(line = BACKWARD_LINE, placement = LabelPlacement.LINE_CENTER, keepUpright = true),
            candidateIndex = 0,
        ).single()
        val asLaid = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(line = BACKWARD_LINE, placement = LabelPlacement.LINE_CENTER, keepUpright = false),
            candidateIndex = 0,
        ).single()

        assertClose(asLaid.anchorPixelX, upright.anchorPixelX, "the anchor does not move")
        assertClose(asLaid.anchorPixelY, upright.anchorPixelY, "the anchor does not move")

        val last = LINE_GLYPH_LOCAL_X.size - 1
        for (index in LINE_GLYPH_LOCAL_X.indices) {
            val frame = recoverGlyphFrame(upright.quads[index], LINE_GLYPH_CELL_WIDTH, LINE_GLYPH_MID_LOCAL_Y)
            assertTrue(frame.axisX > 0.0, "upright glyph $index reads rightward, not ${frame.axisX}")
            assertClose(0.0, run.nearestOn(frame.originX, frame.originY).offLine, "upright glyph $index is on the line")
        }
        assertTrue(
            frameOf(upright, 0).originX < frameOf(upright, last).originX,
            "upright text runs left to right across the screen",
        )

        for (index in LINE_GLYPH_LOCAL_X.indices) {
            val frame = recoverGlyphFrame(asLaid.quads[index], LINE_GLYPH_CELL_WIDTH, LINE_GLYPH_MID_LOCAL_Y)
            assertTrue(frame.axisX < 0.0, "as-laid glyph $index reads leftward, not ${frame.axisX}")
        }
        assertTrue(
            frameOf(asLaid, 0).originX > frameOf(asLaid, last).originX,
            "text laid as the line runs goes right to left",
        )
    }

    /**
     * A left-to-right line is unaffected by the property, which is the other half of the claim: the
     * flip is a repair applied where it is needed, not a transform applied everywhere.
     */
    @Test
    fun keepUprightChangesNothingOnALeftToRightLine() {
        val camera = resolvedPlacementCamera()
        val upright = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(placement = LabelPlacement.LINE_CENTER, keepUpright = true),
            candidateIndex = 0,
        ).single()
        val asLaid = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(placement = LabelPlacement.LINE_CENTER, keepUpright = false),
            candidateIndex = 0,
        ).single()

        for (index in LINE_GLYPH_LOCAL_X.indices) {
            assertSameCorners(
                upright.quads[index].cornersXy,
                asLaid.quads[index].cornersXy,
                "glyph $index",
            )
        }
    }

    // ---- collision inputs --------------------------------------------------------------------

    /**
     * The instance's collision box is the bent cells' own rectangle grown by `text-padding` on all
     * four sides, and the padding moves no ink.
     *
     * The engine's `boundingBox` is not used at all here and cannot be: it describes the horizontal
     * row Rentile laid out, which a bent label does not occupy. That is why applying `padding` here
     * is not the double count point placement's own case guards against.
     */
    @Test
    fun paddingGrowsTheLineCollisionBoxOnAllFourSidesAndMovesNoGlyph() {
        val camera = resolvedPlacementCamera()
        val bare = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(placement = LabelPlacement.LINE_CENTER, padding = 0.0),
            candidateIndex = 0,
        ).single()
        val padded = layOutLineLabels(
            camera,
            PLACEMENT_ATLAS,
            lineCandidate(placement = LabelPlacement.LINE_CENTER, padding = 17.0),
            candidateIndex = 0,
        ).single()

        assertClose(bare.collisionBox.left - 17.0, padded.collisionBox.left, "left")
        assertClose(bare.collisionBox.top - 17.0, padded.collisionBox.top, "top")
        assertClose(bare.collisionBox.right + 17.0, padded.collisionBox.right, "right")
        assertClose(bare.collisionBox.bottom + 17.0, padded.collisionBox.bottom, "bottom")

        for (index in LINE_GLYPH_LOCAL_X.indices) {
            assertSameCorners(
                bare.quads[index].cornersXy,
                padded.quads[index].cornersXy,
                "glyph $index",
            )
        }

        // And the bare box really is the corners' own rectangle, so the padding above is the only
        // expansion in it.
        var left = Double.POSITIVE_INFINITY
        var top = Double.POSITIVE_INFINITY
        var right = Double.NEGATIVE_INFINITY
        var bottom = Double.NEGATIVE_INFINITY
        for (quad in bare.quads) {
            for (corner in 0 until 4) {
                left = minOf(left, quad.cornersXy[corner * 2].toDouble())
                right = maxOf(right, quad.cornersXy[corner * 2].toDouble())
                top = minOf(top, quad.cornersXy[corner * 2 + 1].toDouble())
                bottom = maxOf(bottom, quad.cornersXy[corner * 2 + 1].toDouble())
            }
        }
        assertClose(left, bare.collisionBox.left, "the bare box hugs the corners", FLOAT_PIXEL_TOLERANCE)
        assertClose(top, bare.collisionBox.top, "the bare box hugs the corners", FLOAT_PIXEL_TOLERANCE)
        assertClose(right, bare.collisionBox.right, "the bare box hugs the corners", FLOAT_PIXEL_TOLERANCE)
        assertClose(bottom, bare.collisionBox.bottom, "the bare box hugs the corners", FLOAT_PIXEL_TOLERANCE)
    }

    /**
     * `text-translate` moves the whole instance on screen, in the frame `text-translate-anchor`
     * names, and does not take the label off the line by moving its anchor before the walk.
     *
     * At a bearing of 37.5 degrees the two frames disagree, which is what makes the map-aligned
     * assertion mean something; at a bearing of zero they must agree, which is what pins the
     * direction the map frame turns in.
     */
    @Test
    fun aTranslateMovesTheWholeInstanceInTheFrameItNames() {
        val camera = resolvedPlacementCamera()
        val untranslated = lineInstance(camera, lineCandidate(placement = LabelPlacement.LINE_CENTER))
        val translated = lineInstance(
            camera,
            lineCandidate(
                placement = LabelPlacement.LINE_CENTER,
                translateX = 13.0,
                translateY = -29.0,
            ),
        )

        assertClose(untranslated.anchorPixelX + 13.0, translated.anchorPixelX, "the anchor moves with it")
        assertClose(untranslated.anchorPixelY - 29.0, translated.anchorPixelY, "the anchor moves with it")
        for (index in LINE_GLYPH_LOCAL_X.indices) {
            val before = untranslated.quads[index].cornersXy
            val after = translated.quads[index].cornersXy
            for (corner in 0 until 4) {
                assertClose(
                    before[corner * 2] + 13.0,
                    after[corner * 2].toDouble(),
                    "glyph $index corner $corner x",
                    FLOAT_PIXEL_TOLERANCE,
                )
                assertClose(
                    before[corner * 2 + 1] - 29.0,
                    after[corner * 2 + 1].toDouble(),
                    "glyph $index corner $corner y",
                    FLOAT_PIXEL_TOLERANCE,
                )
            }
        }

        val unturned = resolvedPlacementCamera(placementCamera(bearing = 0.0))
        val mapAligned = lineInstance(
            unturned,
            lineCandidate(
                placement = LabelPlacement.LINE_CENTER,
                translateX = 13.0,
                translateY = -29.0,
                translateAlignment = SymbolAlignment.MAP,
            ),
        )
        val viewportAligned = lineInstance(
            unturned,
            lineCandidate(
                placement = LabelPlacement.LINE_CENTER,
                translateX = 13.0,
                translateY = -29.0,
                translateAlignment = SymbolAlignment.VIEWPORT,
            ),
        )
        assertClose(viewportAligned.anchorPixelX, mapAligned.anchorPixelX, "the two frames agree at bearing zero")
        assertClose(viewportAligned.anchorPixelY, mapAligned.anchorPixelY, "the two frames agree at bearing zero")

        val turned = lineInstance(
            camera,
            lineCandidate(
                placement = LabelPlacement.LINE_CENTER,
                translateX = 13.0,
                translateY = -29.0,
                translateAlignment = SymbolAlignment.MAP,
            ),
        )
        assertTrue(
            abs(turned.anchorPixelX - translated.anchorPixelX) > 1.0,
            "and disagree at 37.5 degrees",
        )
    }

    // ---- refusals ----------------------------------------------------------------------------

    /**
     * Every input line placement reads that point placement does not, in the state that would let it
     * through an arithmetic silently.
     *
     * The first case is the one worth the file: `turn > NaN` is false for every turn, so a ceiling
     * that reached the comparison as `NaN` would admit **every** bend rather than refuse them, and
     * the failure would look like line placement ignoring `text-max-angle` on exactly the styles
     * whose expression for it did not evaluate.
     *
     * **Every case here needs a fixture that would otherwise place**, which is not automatic and was
     * not true of the first one when it was written: deleting the ceiling's own guard left the suite
     * green, because that candidate was a `line` instance too long for `BENT_LINE` and placed
     * nothing for a reason that had nothing to do with the ceiling. The closing control asserts the
     * shared candidate does place, and the ones that vary the line say so themselves.
     */
    @Test
    fun unusableLineInputsPlaceNothing() {
        val camera = resolvedPlacementCamera()
        for ((description, candidate) in listOf(
            // `line-center`, not `line`: at the default spacing a `line` instance on `BENT_LINE`
            // does not fit anyway, so the case would have passed with the ceiling's guard deleted.
            // It did -- the mutation survived until this line said `LINE_CENTER`.
            "a NaN bend ceiling" to lineCandidate(
                line = BENT_LINE,
                placement = LabelPlacement.LINE_CENTER,
                maxAngleDegrees = Double.NaN,
            ),
            "a NaN padding" to lineCandidate(padding = Double.NaN),
            "a negative padding" to lineCandidate(padding = -1.0),
            "a NaN spacing" to lineCandidate(symbolSpacing = Double.NaN),
            "a zero spacing" to lineCandidate(symbolSpacing = 0.0),
            "a sub-pixel spacing" to lineCandidate(symbolSpacing = 0.5),
            "an infinite spacing" to lineCandidate(symbolSpacing = Double.POSITIVE_INFINITY),
            "no line at all" to lineCandidate(line = emptyList()),
            "a one-point line" to lineCandidate(line = listOf(LONG_LINE[0])),
            "a line of one repeated point" to lineCandidate(line = listOf(LONG_LINE[0], LONG_LINE[0])),
            "no glyphs" to lineCandidate(glyphs = emptyList()),
            "a glyph naming no entry" to lineCandidate(
                glyphs = listOf(LabelGlyphQuad(entryIndex = 7, x = 0.0, y = 0.0, scale = 1.0)),
            ),
            "a line entirely behind the camera" to lineCandidate(
                line = listOf(
                    LabelLinePoint(
                        longitude = PLACEMENT_ANCHOR_BEHIND.unwrappedLongitude,
                        latitude = PLACEMENT_ANCHOR_BEHIND.latitude,
                    ),
                    LabelLinePoint(
                        longitude = PLACEMENT_ANCHOR_BEHIND.unwrappedLongitude + 0.001,
                        latitude = PLACEMENT_ANCHOR_BEHIND.latitude + 0.001,
                    ),
                ),
            ),
            "a non-finite line point" to lineCandidate(
                line = listOf(LONG_LINE[0], LabelLinePoint(longitude = Double.NaN, latitude = 48.8), LONG_LINE[3]),
            ),
        )) {
            assertEquals(
                emptyList(),
                layOutLineLabels(camera, PLACEMENT_ATLAS, candidate, candidateIndex = 0),
                description,
            )
        }

        // The control: the same candidate with all of those fields usable does place, so the case
        // above is not simply describing a fixture that never places.
        assertEquals(1, layOutLineLabels(camera, PLACEMENT_ATLAS, lineCandidate(), candidateIndex = 0).size)
    }

    // ---- through the collision pass ----------------------------------------------------------

    /**
     * Line candidates reach collision on the same terms as point ones, and the repeats of one
     * candidate compete with **each other**.
     *
     * That last clause is the reason `placeLabels` iterates a list: two repeats of one road name 30
     * pixels apart overlap, and a pass that inserted only the first instance of each candidate into
     * the index -- or treated the whole candidate as one placement -- would draw them all.
     */
    @Test
    fun repeatsOfOneLineCandidateCollideWithEachOther() {
        val camera = resolvedPlacementCamera()
        val crowded = placeLabels(
            camera,
            placementBatch(lineCandidate(symbolSpacing = 30.0, glyphs = shortLineGlyphRow())),
        )
        val permitted = placeLabels(
            camera,
            placementBatch(
                lineCandidate(
                    symbolSpacing = 30.0,
                    glyphs = shortLineGlyphRow(),
                    overlap = SymbolOverlap.ALWAYS,
                ),
            ),
        )

        assertEquals(5, permitted.size, "every repeat is placed when overlap is permitted")
        assertEquals(3, crowded.size, "and two of the five lose their place when it is not")
        assertTrue(crowded.all { it.candidateIndex == 0 }, "all of them are the one candidate")
    }

    /**
     * All three placement modes reach a placement now, which is what replaced task 9's pin that line
     * candidates were passed over.
     */
    @Test
    fun everyPlacementModeIsPlaced() {
        val camera = resolvedPlacementCamera()
        val placed = placeLabels(
            camera,
            placementBatch(
                lineCandidate(sortKey = 9.0, placement = LabelPlacement.LINE, symbolSpacing = 250.0),
                lineCandidate(sortKey = 8.0, placement = LabelPlacement.LINE_CENTER, line = BENT_LINE),
                // `always`, because the point candidate's anchor sits close enough to the line
                // that it would otherwise lose the collision to the line label placed before it --
                // which is correct behaviour and not what this case is about.
                placementCandidate(
                    sortKey = 7.0,
                    placement = LabelPlacement.POINT,
                    overlap = SymbolOverlap.ALWAYS,
                ),
            ),
        )

        // The second candidate is on `BENT_LINE`, which the default 45-degree ceiling refuses, so
        // two of the three are placed and the missing one is missing for a reason this file states.
        assertEquals(listOf(2, 0), placed.map { it.candidateIndex })
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun lineInstance(
        camera: ResolvedMercatorCamera,
        candidate: LabelCandidate,
    ): PlacedLabel = layOutLineLabels(camera, PLACEMENT_ATLAS, candidate, candidateIndex = 0).single()

    private fun frameOf(label: PlacedLabel, index: Int): RecoveredFrame =
        recoverGlyphFrame(label.quads[index], LINE_GLYPH_CELL_WIDTH, LINE_GLYPH_MID_LOCAL_Y)

    private fun assertSameCorners(expected: FloatArray, actual: FloatArray, description: String) {
        assertEquals(expected.size, actual.size, "$description component count")
        for (index in expected.indices) {
            assertEquals(expected[index], actual[index], "$description [$index]")
        }
    }

    private fun assertClose(
        expected: Double,
        actual: Double,
        description: String = "",
        tolerance: Double = PIXEL_TOLERANCE,
    ) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$description: expected $expected but was $actual",
        )
    }

    private fun assertClose(expected: Float, actual: Double, description: String, tolerance: Double) {
        assertClose(expected.toDouble(), actual, description, tolerance)
    }

    private companion object {
        /**
         * Anchors and recovered origins stay in `Double`, but they are recovered from quad corners
         * that were rounded to `Float` at around 700 pixels, where the representation is about
         * 6e-5. Nothing asserted at this tolerance is a near miss.
         */
        const val PIXEL_TOLERANCE: Double = 1e-3

        /** A corner is a `Float`; a difference of two of them carries twice its representation. */
        const val FLOAT_PIXEL_TOLERANCE: Double = 1e-3

        /**
         * `LONG_LINE`'s two outer segments differ by 4.766 degrees, whose cosines differ by about
         * 0.045. Half of that separates "the label bent" from "the label did not".
         */
        const val BENT_AXIS_SEPARATION: Double = 0.02

        /**
         * Two glyphs on one segment share a tangent exactly, but their axes are recovered from
         * corners already rounded to `Float` at around 700 pixels and then divided by a 9-pixel
         * cell, which carries that rounding up to about 7e-6. Four orders of magnitude below the
         * 0.045 that separates this fixture's two segments.
         */
        const val SHARED_AXIS_TOLERANCE: Double = 1e-4

        /** Well inside the 14.6-pixel margin the `line-center` fixtures keep from their vertices. */
        const val VERTEX_CLEARANCE_PIXELS: Double = 5.0
    }
}
