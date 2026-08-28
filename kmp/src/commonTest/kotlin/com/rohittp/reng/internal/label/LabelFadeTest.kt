package com.rohittp.reng.internal.label

import com.rohittp.reng.Camera
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.rentile.LabelCandidate
import com.rohittp.rentile.LabelCandidateBatch
import com.rohittp.rentile.LabelGlyphAtlas
import com.rohittp.rentile.LabelGlyphEntry
import com.rohittp.rentile.LabelGlyphQuad
import com.rohittp.rentile.LabelLayerStyle
import com.rohittp.rentile.LabelPlacement
import com.rohittp.rentile.SymbolOverlap
import com.rohittp.rentile.TileId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ADR 0035's fade, and the identity it turns on.
 *
 * **Every case here is three frames or more, and that is not padding.** A two-frame case that clears
 * history between the frames tests the historyless path twice and passes with the whole feature
 * deleted; a two-frame case that does not clear it cannot tell a fade that resumed from one that
 * restarted. The shape that discriminates is A, B, then A again, asserting the third phase differs
 * from the first -- and it discriminates only if the first phase ran long enough that a single
 * absence cannot undo it, which is why phase one is three frames rather than one.
 *
 * The second trap is sampling. An eased value read where it happens to be 0 or 1 cannot be told
 * apart from a step function, so every opacity assertion below is taken mid-fade, at a step where
 * the two answers differ.
 */
class LabelFadeTest {
    @Test
    fun identityIgnoresEverythingThatChangesFromFrameToFrame() {
        // One label, described by two batches that agree on nothing a renderer is free to vary. If
        // any of these reaches the identity, fade never matches its own previous frame: every label
        // restarts from zero, everything pops exactly as it did before fade existed, and no
        // assertion about an opacity lying in [0, 1] notices.
        val firstFrame = fadeBatch(
            // A decoy ahead of it, so the label's own candidate index differs between the frames.
            placementCandidate(position = OTHER_ANCHOR, layerStyleIndex = 1),
            placementCandidate(
                glyphs = listOf(glyphOf(entryIndex = 0), glyphOf(entryIndex = 1)),
                sortKey = 5.0,
                color = 0x11223344,
                opacity = 1.0,
                haloWidth = 0.0,
            ),
            layers = listOf(labelLayer("place-city", zoom = 13, priority = 3), labelLayer("road-label")),
            atlas = atlasOf(letter('R'), letter('S')),
            contentKey = "frame-one",
        )
        val secondFrame = fadeBatch(
            placementCandidate(
                // The same two letters, out of an atlas that packed them the other way round.
                glyphs = listOf(
                    glyphOf(entryIndex = 1, x = -3.5, y = 9.25, scale = 2.5),
                    glyphOf(entryIndex = 0, x = 41.0, y = 9.25, scale = 2.5),
                ),
                sortKey = -2.0,
                color = 0xF0E0D0C0.toInt(),
                opacity = 0.25,
                haloWidth = 4.0,
            ),
            layers = listOf(labelLayer("place-city", zoom = 17, priority = 91)),
            atlas = atlasOf(letter('S'), letter('R')),
            contentKey = "frame-two",
        )

        assertEquals(
            deriveLabelIdentity(firstFrame, 1, lineRepeat = null),
            deriveLabelIdentity(secondFrame, 0, lineRepeat = null),
        )
    }

    @Test
    fun identitySeparatesLabelsThatDifferInWhatTheyAre() {
        // The other silent failure: identities that collide, so one label inherits another's
        // opacity. Each variant below changes exactly one thing a human would call a different
        // label, and all eight must land on eight identities.
        val base = fadeBatch(placementCandidate())
        val variants = listOf(
            base,
            fadeBatch(placementCandidate(), layers = listOf(labelLayer("place-town"))),
            fadeBatch(placementCandidate(sourceTile = TileId(z = 14, x = 4237, y = 2887))),
            fadeBatch(placementCandidate(sourceTile = TileId(z = 13, x = 4238, y = 2887))),
            fadeBatch(placementCandidate(sourceTile = TileId(z = 13, x = 4237, y = 2888))),
            fadeBatch(placementCandidate(position = OTHER_ANCHOR)),
            fadeBatch(
                placementCandidate(
                    position = GeographicPosition(
                        latitude = PLACEMENT_ANCHOR.latitude,
                        unwrappedLongitude = OTHER_ANCHOR.unwrappedLongitude,
                        altitudeMetres = 0.0,
                    ),
                ),
            ),
            fadeBatch(placementCandidate(), atlas = atlasOf(letter('S'))),
            fadeBatch(placementCandidate(glyphs = listOf(glyphOf(0), glyphOf(0)))),
        )

        val identities = variants.map { assertNotNull(deriveLabelIdentity(it, 0, lineRepeat = null)) }
        assertEquals(variants.size, identities.toSet().size)
    }

    @Test
    fun identityIsAbsentRatherThanApproximateWhenTheBatchCannotDescribeOne() {
        val batch = fadeBatch(placementCandidate())
        assertNull(deriveLabelIdentity(batch, 1, lineRepeat = null))
        val noLayer = fadeBatch(placementCandidate(layerStyleIndex = 4))
        assertNull(deriveLabelIdentity(noLayer, 0, lineRepeat = null))
        val noGlyph = fadeBatch(placementCandidate(glyphs = listOf(glyphOf(7))))
        assertNull(deriveLabelIdentity(noGlyph, 0, lineRepeat = null))
        assertNull(
            deriveLabelIdentity(
                fadeBatch(
                    placementCandidate(
                        position = GeographicPosition(
                            latitude = Double.NaN,
                            unwrappedLongitude = PLACEMENT_ANCHOR.unwrappedLongitude,
                            altitudeMetres = 0.0,
                        ),
                    ),
                ),
                0,
                lineRepeat = null,
            ),
        )
        // The repeat's own distance is checked on the same terms as the anchor: `binary64` refuses a
        // non-finite Double by design, and a derivation that throws is a frame failed over a fade.
        assertNull(
            deriveLabelIdentity(
                batch,
                0,
                lineRepeat = LineRepeat(runIndex = 0, anchorDistancePixels = Double.NaN),
            ),
        )
    }

    @Test
    fun everyRepeatOfOneLineCandidateGetsItsOwnIdentity() {
        // The seam between line placement and fade. One `line` candidate places its name several
        // times along its own road, and every repeat carries that one candidate's index -- so an
        // identity derived from the candidate alone makes them one label sharing one opacity.
        val camera = resolvedPlacementCamera()
        val batch = fadeBatch(
            lineCandidate(
                line = BROKEN_LINE,
                symbolSpacing = 30.0,
                glyphs = shortLineGlyphRow(),
                // Repeats 30 pixels apart overlap, and this case is about identity rather than
                // about collision, so every one of them is allowed to keep its place.
                overlap = SymbolOverlap.ALWAYS,
            ),
        )
        val placed = placeLabels(camera, batch)

        // **The fixture is the case.** A line yielding one repeat cannot tell a per-repeat identity
        // from a fixed one, so this one yields three; and neither half of `LineRepeat` separates
        // all three by itself, which is what makes the assertion below evidence for both. Two of
        // the three sit on one run, and two of the three -- a different two -- sit at one arc
        // distance, because `BROKEN_LINE` restarts its arc length at the near-plane gap.
        assertEquals(3, placed.size)
        assertTrue(placed.all { it.candidateIndex == 0 }, "all three are the one candidate")
        val repeats = placed.map { assertNotNull(it.lineRepeat) }
        assertEquals(2, repeats.map { it.runIndex }.toSet().size, "two of the three share a run")
        assertEquals(
            2,
            repeats.map { it.anchorDistancePixels }.toSet().size,
            "two of the three share an arc distance",
        )

        val identities = placed.map {
            assertNotNull(deriveLabelIdentity(batch, it.candidateIndex, it.lineRepeat))
        }
        assertEquals(3, identities.toSet().size, "three repeats, three identities")
        // And through the fade, where the collapse would be one entry advanced three times.
        assertEquals(3, advanceLabelFade(LabelFadeState.EMPTY, batch, placed).nextState.entryCount)
    }

    @Test
    fun aRepeatKeepsOneIdentityAndOneFadeWhileTheCameraMoves() {
        // The other half of the pair, and the half that fails invisibly: an identity built on
        // `anchorPixelX`/`anchorPixelY` separates the repeats exactly as the case above wants and
        // then matches nothing in the next frame, so every label restarts its fade on every frame
        // and fade goes inert with every opacity still in [0, 1]. Same batch, two cameras.
        val batch = fadeBatch(
            lineCandidate(
                symbolSpacing = 60.0,
                glyphs = shortLineGlyphRow(),
                overlap = SymbolOverlap.ALWAYS,
            ),
        )
        val before = placeLabels(resolvedPlacementCamera(), batch)
        val after = placeLabels(resolvedPlacementCamera(pannedPlacementCamera()), batch)

        // Without this the camera might as well not have moved and every reading below is trivial.
        assertEquals(REPEATS_ON_LONG_LINE, before.size, "the fixture repeats")
        assertEquals(before.size, after.size, "the pan keeps the same repeats")
        for (index in before.indices) {
            assertTrue(
                abs(before[index].anchorPixelX - after[index].anchorPixelX) > MOVED_PIXELS &&
                    abs(before[index].anchorPixelY - after[index].anchorPixelY) > MOVED_PIXELS,
                "repeat $index stayed at ${before[index].anchorPixelX}, ${before[index].anchorPixelY}",
            )
        }

        assertEquals(
            before.map { deriveLabelIdentity(batch, it.candidateIndex, it.lineRepeat) },
            after.map { deriveLabelIdentity(batch, it.candidateIndex, it.lineRepeat) },
        )

        // Read through the fade at a step where a carried opacity and a restarted one differ: three
        // frames before the pan and one after it, so an identity that survived the camera move
        // reads 0.4 and one that did not reads 0.1.
        var state = LabelFadeState.EMPTY
        repeat(3) { state = advanceLabelFade(state, batch, before).nextState }
        val moved = advanceLabelFade(state, batch, after)

        assertEquals(List(before.size) { 0.4f }, moved.labels.map { it.opacity })
        assertEquals(before.size, moved.nextState.entryCount)
    }

    @Test
    fun aLineCentreLabelKeepsOneIdentityWhileTheCameraMoves() {
        // `line-center` places one instance per candidate, so it has nothing to be told apart from
        // -- and the only along-line distance it has is half a **projected** run's length, which is
        // a different number every time the camera moves. Carrying it would restart every
        // `line-center` label's fade on every frame, which is the same silent inertness the screen
        // anchor causes and is invisible to the case above, whose candidate is a `line` one.
        val settled = resolvedPlacementCamera()
        val panned = resolvedPlacementCamera(pannedPlacementCamera())
        val batch = fadeBatch(
            lineCandidate(placement = LabelPlacement.LINE_CENTER, glyphs = shortLineGlyphRow()),
        )
        val before = placeLabels(settled, batch).single()
        val after = placeLabels(panned, batch).single()

        // Both halves of the non-vacuity: the label moved on screen, and -- the one this case turns
        // on -- the run it halves is a different length under the two cameras, so a `line-center`
        // distance admitted to the identity really would be a different number in the second frame.
        assertTrue(
            abs(before.anchorPixelX - after.anchorPixelX) > MOVED_PIXELS,
            "the anchor did not move",
        )
        val settledLength = screenRunOf(settled, LONG_LINE).length
        val pannedLength = screenRunOf(panned, LONG_LINE).length
        assertTrue(
            abs(settledLength - pannedLength) > MOVED_PIXELS,
            "the run is $settledLength pixels under both cameras",
        )

        var state = LabelFadeState.EMPTY
        repeat(3) { state = advanceLabelFade(state, batch, listOf(before)).nextState }
        val moved = advanceLabelFade(state, batch, listOf(after))

        assertEquals(0.4f, moved.labels.single().opacity)
        assertEquals(1, moved.nextState.entryCount)
    }

    @Test
    fun aLabelPlacedAgainAfterOneAbsentFrameResumesRatherThanRestarting() {
        // The discriminating shape: A, B, A. Phase one runs three frames so that a single absent
        // frame cannot undo it -- with a one-frame phase one the rise and the decay cancel exactly
        // and the third phase reads the same as the first under both a working fade and a broken
        // one.
        val camera = resolvedPlacementCamera()
        val present = fadeBatch(placementCandidate())
        val blocked = fadeBatch(
            placementCandidate(),
            // Same pixels, higher `symbol-sort-key`, so this one places first and ours loses.
            placementCandidate(sortKey = 1.0, layerStyleIndex = 1, position = PLACEMENT_ANCHOR),
            layers = listOf(labelLayer("place-city"), labelLayer("place-capital")),
        )

        var state = LabelFadeState.EMPTY
        val firstPhase = mutableListOf<Float>()
        repeat(3) {
            val advance = advanceLabelFade(state, present, placeLabels(camera, present))
            firstPhase += advance.labels.single().opacity
            state = advance.nextState
        }

        val absent = advanceLabelFade(state, blocked, placeLabels(camera, blocked))
        assertEquals(1, absent.labels.size)
        state = absent.nextState

        val thirdPhase = advanceLabelFade(state, present, placeLabels(camera, present))

        assertEquals(listOf(0.1f, 0.2f, 0.3f), firstPhase)
        assertNotEquals(firstPhase.first(), thirdPhase.labels.single().opacity)
        assertEquals(0.3f, thirdPhase.labels.single().opacity)
    }

    @Test
    fun clearedHistoryRepeatsAFrameAndCarriedHistoryDoesNot() {
        // Both halves, because either alone is vacuous: the first passes with the feature deleted,
        // and the second passes with the state cleared on every frame.
        val camera = resolvedPlacementCamera()
        val batch = fadeBatch(placementCandidate())
        val placed = placeLabels(camera, batch)

        val fromCleared = advanceLabelFade(LabelFadeState.EMPTY, batch, placed)
        val fromClearedAgain = advanceLabelFade(LabelFadeState.EMPTY, batch, placed)
        val fromHistory = advanceLabelFade(fromCleared.nextState, batch, placed)

        assertEquals(fromCleared.labels.single().opacity, fromClearedAgain.labels.single().opacity)
        assertNotEquals(fromCleared.labels.single().opacity, fromHistory.labels.single().opacity)
        assertEquals(0.1f, fromCleared.labels.single().opacity)
        assertEquals(0.2f, fromHistory.labels.single().opacity)
    }

    @Test
    fun advancingTwiceFromOneStateAnswersTwice() {
        // The state argument is inert data, which is what lets the caller commit the result only
        // after every fallible step of `prepare` has succeeded -- and what keeps ADR 0035's rule
        // that drawing never changes history reachable at all.
        val camera = resolvedPlacementCamera()
        val batch = fadeBatch(placementCandidate())
        val placed = placeLabels(camera, batch)
        val identity = assertNotNull(deriveLabelIdentity(batch, 0, lineRepeat = null))
        var state = LabelFadeState.EMPTY
        repeat(3) { state = advanceLabelFade(state, batch, placed).nextState }

        val once = advanceLabelFade(state, batch, placed)
        val twice = advanceLabelFade(state, batch, placed)

        assertEquals(0.4f, once.labels.single().opacity)
        assertEquals(once.labels.single().opacity, twice.labels.single().opacity)
        assertEquals(3, state.stepOf(identity))
    }

    @Test
    fun fadeRisesInStepsAndStopsAtOpaque() {
        val camera = resolvedPlacementCamera()
        val batch = fadeBatch(placementCandidate())
        val placed = placeLabels(camera, batch)

        var state = LabelFadeState.EMPTY
        val opacities = (1..LABEL_FADE_STEPS + 2).map {
            val advance = advanceLabelFade(state, batch, placed)
            state = advance.nextState
            advance.labels.single().opacity
        }

        assertEquals(0.5f, opacities[4])
        assertEquals(1.0f, opacities[LABEL_FADE_STEPS - 1])
        assertEquals(1.0f, opacities.last())
    }

    @Test
    fun theFadeMultipliesIntoThePaintTheStyleAlreadySet() {
        // `text-opacity` is 0.4 rather than 1.0 on purpose: at 1.0 multiplying by the fade is the
        // identity on the style's own value, and the case would pass against an implementation that
        // overwrote the paint instead of scaling it.
        val camera = resolvedPlacementCamera()
        val batch = fadeBatch(placementCandidate(opacity = 0.4, haloWidth = 2.5, haloBlur = 0.75))
        val placed = placeLabels(camera, batch)

        var state = LabelFadeState.EMPTY
        var advance = advanceLabelFade(state, batch, placed)
        repeat(2) {
            state = advance.nextState
            advance = advanceLabelFade(state, batch, placed)
        }
        val faded = advance.labels.single()

        assertEquals(0.3f, faded.opacity)
        assertEquals(0.4f * 0.3f, faded.quads.single().paint.opacity)
        // Everything else the style set survives the multiplication, and the placed label itself is
        // untouched: a Prepared Frame's own paints are not rewritten by the frame after it.
        assertEquals(2.5f, faded.quads.single().paint.haloWidthPixels)
        assertEquals(0.75f, faded.quads.single().paint.haloBlurPixels)
        assertEquals(
            faded.label.quads.single().paint.textColour.toList(),
            faded.quads.single().paint.textColour.toList(),
        )
        assertEquals(0.4f, faded.label.quads.single().paint.opacity)
    }

    @Test
    fun anOpaqueLabelIsHandedItsOwnQuadsBack() {
        val camera = resolvedPlacementCamera()
        val batch = fadeBatch(placementCandidate())
        val placed = placeLabels(camera, batch)

        var state = LabelFadeState.EMPTY
        var advance = advanceLabelFade(state, batch, placed)
        repeat(LABEL_FADE_STEPS - 1) {
            state = advance.nextState
            advance = advanceLabelFade(state, batch, placed)
        }

        assertEquals(1.0f, advance.labels.single().opacity)
        assertSame(advance.labels.single().label.quads, advance.labels.single().quads)
    }

    @Test
    fun theStateDropsALabelItCanNoLongerSee() {
        // Self-bounding, half one: what rose has to come back down and then leave. An entry retained
        // one frame too long is harmless; an entry retained forever is a leak that only shows up in
        // a session nobody runs in a test.
        val camera = resolvedPlacementCamera()
        val batch = fadeBatch(placementCandidate())
        val placed = placeLabels(camera, batch)
        var state = LabelFadeState.EMPTY
        repeat(4) { state = advanceLabelFade(state, batch, placed).nextState }
        assertEquals(1, state.entryCount)

        val remaining = (1..4).map {
            state = advanceLabelFade(state, batch = null, placed = emptyList()).nextState
            state.entryCount
        }

        assertEquals(listOf(1, 1, 1, 0), remaining)
    }

    @Test
    fun aLongPanOverFreshLabelsDoesNotGrowTheState() {
        // Self-bounding, half two, and the half a fixture-sized case cannot reach: two hundred
        // frames, a different label in each, is exactly the shape that turns "remember every label"
        // into an unbounded map.
        val camera = resolvedPlacementCamera()
        var state = LabelFadeState.EMPTY
        var largest = 0
        var placements = 0
        repeat(FRAMES_SWEPT) { frame ->
            val batch = fadeBatch(
                placementCandidate(
                    position = GeographicPosition(
                        latitude = PLACEMENT_ANCHOR.latitude + frame * 0.000_01,
                        unwrappedLongitude = PLACEMENT_ANCHOR.unwrappedLongitude,
                        altitudeMetres = 0.0,
                    ),
                ),
            )
            val advance = advanceLabelFade(state, batch, placeLabels(camera, batch))
            placements += advance.labels.size
            state = advance.nextState
            largest = maxOf(largest, state.entryCount)
        }

        // Without this the case measures a state nothing ever entered: a label that drifted off the
        // viewport is never placed, so an unbounded map would stay empty and the assertion below
        // would pass against the leak it exists to catch.
        assertEquals(FRAMES_SWEPT, placements)
        assertTrue(largest <= LABEL_FADE_STEPS, "the fade state grew to $largest entries")
    }

    @Test
    fun twoCopiesOfOneLabelShareOneFadeRatherThanRunningItTwice() {
        // A feature a source duplicates, or one a tile buffer repeats, derives one identity. If the
        // rise were read from the map being built rather than from the previous state, the fade
        // would run at twice speed on exactly the labels that are duplicated.
        val camera = resolvedPlacementCamera()
        val batch = fadeBatch(
            placementCandidate(overlap = SymbolOverlap.ALWAYS, ignorePlacement = true),
            placementCandidate(overlap = SymbolOverlap.ALWAYS, ignorePlacement = true),
        )
        val placed = placeLabels(camera, batch)
        assertEquals(2, placed.size)

        var state = LabelFadeState.EMPTY
        var advance = advanceLabelFade(state, batch, placed)
        repeat(2) {
            state = advance.nextState
            advance = advanceLabelFade(state, batch, placed)
        }

        assertEquals(listOf(0.3f, 0.3f), advance.labels.map { it.opacity })
        assertEquals(1, advance.nextState.entryCount)
    }

    @Test
    fun aLabelWithNoIdentityIsDrawnExactlyAsItWasBeforeFadeExisted() {
        val camera = resolvedPlacementCamera()
        val batch = fadeBatch(placementCandidate(layerStyleIndex = 3))
        val placed = placeLabels(camera, batch)
        assertEquals(1, placed.size)

        val advance = advanceLabelFade(LabelFadeState.EMPTY, batch, placed)

        assertEquals(1.0f, advance.labels.single().opacity)
        assertEquals(0, advance.nextState.entryCount)
    }

    @Test
    fun aFrameThatPlacesNothingFadesEveryCarriedLabelOut() {
        // The `drawLabels = false` frame, which is also every frame RenGRenderer prepares until the
        // label handover is wired into preparation.
        val camera = resolvedPlacementCamera()
        val batch = fadeBatch(placementCandidate())
        val placed = placeLabels(camera, batch)
        val identity = assertNotNull(deriveLabelIdentity(batch, 0, lineRepeat = null))
        var state = LabelFadeState.EMPTY
        repeat(6) { state = advanceLabelFade(state, batch, placed).nextState }
        assertEquals(6, state.stepOf(identity))

        val next = advanceLabelFade(state, batch = null, placed = emptyList())

        assertEquals(5, next.nextState.stepOf(identity))
        assertEquals(0, next.labels.size)
    }
}

private const val FRAMES_SWEPT: Int = 200

/** `LONG_LINE`'s 175.754 screen pixels hold three repeats at a 60-pixel spacing: 30, 90 and 150. */
private const val REPEATS_ON_LONG_LINE: Int = 3

/** Well above the `Float` corner rounding the anchors are recovered through, and far below the pan. */
private const val MOVED_PIXELS: Double = 1.0

/** How far east [pannedPlacementCamera] moves: enough to move every anchor, too little to reshape a run. */
private const val PAN_DEGREES: Double = 0.000_4

/**
 * [placementCamera] panned east, derived from it rather than restated so that a fixture edit reaches
 * both frames of the case that uses it.
 */
private fun pannedPlacementCamera(): Camera {
    val base = placementCamera()
    return Camera(
        latitude = base.latitude,
        unwrappedLongitude = base.unwrappedLongitude + PAN_DEGREES,
        zoom = base.zoom,
        bearing = base.bearing,
        pitch = base.pitch,
    )
}

/** The second anchor, far enough from [PLACEMENT_ANCHOR] that no box of one meets a box of the other. */
private val OTHER_ANCHOR: GeographicPosition =
    GeographicPosition(latitude = 48.870_1, unwrappedLongitude = 2.351_7, altitudeMetres = 0.0)

private fun letter(character: Char): LabelGlyphEntry = LabelGlyphEntry(
    fontStackDigest = "0f".repeat(32),
    codepoint = character.code,
    x = 33,
    y = 51,
    width = 12,
    height = 20,
    left = 2,
    top = -17,
    advance = 14,
)

private fun atlasOf(vararg entries: LabelGlyphEntry): LabelGlyphAtlas = LabelGlyphAtlas(
    pngBytes = ByteArray(0),
    width = ATLAS_WIDTH,
    height = ATLAS_HEIGHT,
    contentKey = "fade-atlas",
    entries = entries.toList(),
)

private fun glyphOf(
    entryIndex: Int,
    x: Double = 7.5,
    y: Double = -13.25,
    scale: Double = 0.75,
): LabelGlyphQuad = LabelGlyphQuad(entryIndex = entryIndex, x = x, y = y, scale = scale)

private fun labelLayer(
    layerId: String,
    zoom: Int = 13,
    priority: Int = 3,
): LabelLayerStyle = LabelLayerStyle(layerId = layerId, zoom = zoom, priority = priority)

/**
 * A batch whose layer ids, atlas and content key are all the case's own, because the identity
 * derivation is about exactly those and [placementBatch] derives its layer ids from an index.
 */
private fun fadeBatch(
    vararg candidates: LabelCandidate,
    layers: List<LabelLayerStyle> = listOf(labelLayer("place-city")),
    atlas: LabelGlyphAtlas = atlasOf(letter('R')),
    contentKey: String = "fade-batch",
): LabelCandidateBatch = LabelCandidateBatch(
    candidates = candidates.toList(),
    layerStyles = layers,
    atlas = atlas,
    contentKey = contentKey,
    diagnostics = emptyList(),
)
