package com.rohittp.reng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame

class DrawnThingsTest {
    @Test
    fun animationSelectorAndTimeValidationIsExact() {
        AnimationSelector.Index(0)
        AnimationSelector.Name("é")
        assertFailsWith<IllegalArgumentException> { AnimationSelector.Index(-1) }
        assertFailsWith<IllegalArgumentException> { AnimationSelector.Name(" ") }
        assertFailsWith<IllegalArgumentException> {
            AnimationTrack(AnimationSelector.Index(0), Double.NaN)
        }
    }

    @Test
    fun animationSelectorsPreserveExactUnicodeWithoutNormalization() {
        val composed = AnimationSelector.Name("é")
        val decomposed = AnimationSelector.Name("é")

        assertFalse(composed == decomposed)
        assertFailsWith<IllegalArgumentException> { AnimationSelector.Name("\uD800") }
    }

    @Test
    fun animationTrackCanonicalizesNegativeZeroAndUsesStructuralEquality() {
        val first = AnimationTrack(AnimationSelector.Name("walk"), -0.0)
        val equal = AnimationTrack(AnimationSelector.Name("walk"), 0.0)
        val different = AnimationTrack(AnimationSelector.Name("walk"), 1.0)

        assertEquals(0L, first.timeSeconds.toBits())
        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertFalse(first == different)
    }

    @Test
    fun animationTrackRequiresFiniteNonNegativeTime() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0001).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                AnimationTrack(AnimationSelector.Index(0), invalid)
            }
        }
    }

    @Test
    fun modelCopiesInputAndEveryListResultIncludingEmptyAndSingleton() {
        val track = AnimationTrack(AnimationSelector.Index(0), 1.0)
        assertModelListIsIndependent(emptyList())
        assertModelListIsIndependent(listOf(track))
        assertModelListIsIndependent(listOf(track, track))
    }

    @Test
    fun modelListSnapshotKeepsEqualityAndHashStableAfterMutation() {
        val track = AnimationTrack(AnimationSelector.Index(0), 1.0)
        val input = mutableListOf(track)
        val model = Model(screenPlacement(), ResourceLocator("model"), animationTracks = input)
        val equal = Model(screenPlacement(), ResourceLocator("model"), animationTracks = listOf(track))
        val hashCode = model.hashCode()

        input.clear()
        (model.animationTracks as MutableList<AnimationTrack>).clear()

        assertEquals(listOf(track), model.animationTracks)
        assertEquals(model, equal)
        assertEquals(hashCode, model.hashCode())
        assertEquals(listOf(track), model.animationTracksForCore())
    }

    @Test
    fun shaderPairValidatesSourcesButDoesNotScanProfiles() {
        ShaderPair("not a version directive", "also not a version directive")

        assertFailsWith<IllegalArgumentException> { ShaderPair("", "fragment") }
        assertFailsWith<IllegalArgumentException> { ShaderPair("vertex", "") }
        assertFailsWith<IllegalArgumentException> { ShaderPair("\uD800", "fragment") }
        assertFailsWith<IllegalArgumentException> { ShaderPair("vertex", "\uDFFF") }
    }

    @Test
    fun shaderPairToStringRedactsBothSources() {
        val vertexSource = "vertex-secret"
        val fragmentSource = "fragment-secret"
        val pair = ShaderPair(vertexSource, fragmentSource)

        assertFalse(pair.toString().contains(vertexSource))
        assertFalse(pair.toString().contains(fragmentSource))
    }

    @Test
    fun geometryRequiresStrictNorthWestBoundsAndAtMostOneWorldSpan() {
        val shaderPair = ShaderPair("vertex", "fragment")
        Geometry(Vector3(90.0, -180.0, 0.0), Vector3(-90.0, 180.0, 0.0), shaderPair)

        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(0.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(1.0, 180.0, 0.0), Vector3(0.0, -180.0, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 360.0001, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(90.0001, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(0.0, 0.0, 0.0), Vector3(-90.0001, 1.0, 0.0), shaderPair)
        }
    }

    /**
     * A `Geometry` carries no `Placement` — `CONTEXT.md` says so — so the mode has to reach it on a
     * field of its own, and one mode governs both corners.
     *
     * **The destructuring line is the source-compatibility gate**, not a style choice. [Geometry] is
     * a `data class`, so declaring the new field anywhere but last would renumber `component3`
     * through `component5` and silently re-bind every consumer that destructures one; this
     * statement stops compiling if that ever happens.
     *
     * **Nothing constrains the value.** `Placement` rejects a ground-relative altitude on a
     * screen-anchored position because a screen `z` is a compositing index; a `Geometry` is a
     * geographic rectangle and has no such alternative, so both modes construct on every geometry.
     */
    @Test
    fun aGeometryCarriesItsOwnAltitudeModeDefaultedToAbsolute() {
        val shaderPair = ShaderPair("vertex", "fragment")
        val absolute = Geometry(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), shaderPair)
        val groundRelative = absolute.copy(altitudeMode = AltitudeMode.GROUND_RELATIVE)

        assertEquals(AltitudeMode.ABSOLUTE, absolute.altitudeMode)
        assertEquals(AltitudeMode.GROUND_RELATIVE, groundRelative.altitudeMode)
        assertNotEquals(absolute, groundRelative)
        assertEquals(absolute, absolute.copy(altitudeMode = AltitudeMode.ABSOLUTE))

        val (topLeft, bottomRight, pair, uniforms, textures, altitudeMode) = groundRelative
        assertEquals(Vector3(1.0, 0.0, 0.0), topLeft)
        assertEquals(Vector3(0.0, 1.0, 0.0), bottomRight)
        assertEquals(shaderPair, pair)
        assertEquals(emptyMap(), uniforms)
        assertEquals(emptyMap(), textures)
        assertEquals(AltitudeMode.GROUND_RELATIVE, altitudeMode)
    }

    @Test
    fun geometryToStringRedactsShaderSourcesTransitively() {
        val vertexSource = "vertex-secret"
        val fragmentSource = "fragment-secret"
        val geometry = Geometry(
            Vector3(1.0, 2.0, 3.0),
            Vector3(0.0, 3.0, 4.0),
            ShaderPair(vertexSource, fragmentSource),
        )

        assertFalse(geometry.toString().contains(vertexSource))
        assertFalse(geometry.toString().contains(fragmentSource))
    }

    private fun assertModelListIsIndependent(tracks: List<AnimationTrack>) {
        val input = ArrayList(tracks)
        val model = Model(screenPlacement(), ResourceLocator("model"), animationTracks = input)

        input.clear()
        val first = model.animationTracks
        val returned = first as MutableList<AnimationTrack>
        returned.clear()
        val second = model.animationTracks

        assertEquals(tracks, second)
        assertNotSame(first, second)
        assertEquals(tracks, model.animationTracksForCore())
    }

    private fun screenPlacement(): Placement =
        Placement(
            AnchoringMode.SCREEN,
            Vector3(0.0, 0.0, 0.0),
            AnchoringMode.SCREEN,
            Vector3(0.0, 0.0, 0.0),
            AnchoringMode.SCREEN,
            1.0,
        )
}
