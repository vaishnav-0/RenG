package com.rohittp.reng.internal.identity

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * ADR 0058's substance: the platform's SHA-256 and RenG's own must agree byte for byte, on every
 * target, for every input shape.
 *
 * This is not a formality. A SHA-256 is an **identity** in RenG, not only a check — `ResourceKey`'s
 * `stableId` is one, content-addressed locators are one, and the firewall compares a transport digest
 * with a store digest. An implementation disagreeing by a byte would not fail loudly; it would split
 * a content-addressed cache across platforms, so a store written by an Android build would have every
 * record refused as corrupt by an iOS one.
 *
 * The vectors are chosen for where a SHA-256 implementation actually goes wrong: the block boundary,
 * and the length field that must fit in the final block.
 */
class PlatformSha256EquivalenceTest {
    @Test
    fun theTwoImplementationsAgreeOnEveryShapeThatMatters() {
        val sizes = listOf(
            0,     // nothing to hash
            1,
            55,    // the last size whose length field still fits the first block
            56,    // the first size that forces a second block
            63,
            64,    // exactly one block
            65,
            127,
            128,
            1024,
            64 * 1024,
            1024 * 1024,
        )

        sizes.forEach { size ->
            val bytes = ByteArray(size) { (it * 31 + 7).toByte() }
            val reference = PureKotlinSha256.digest(CanonicalBytes(bytes))
            val accelerated = AcceleratedSha256.digest(CanonicalBytes(bytes))
            assertEquals(
                reference.lowercaseHex,
                accelerated.lowercaseHex,
                "the platform digest disagrees at $size bytes",
            )
        }
    }

    @Test
    fun theKnownAnswerIsTheKnownAnswer() {
        // FIPS 180-4's own first vector, so that "the two agree" cannot be satisfied by two
        // implementations being wrong together.
        val abc = byteArrayOf(0x61, 0x62, 0x63)
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AcceleratedSha256.digest(CanonicalBytes(abc)).lowercaseHex,
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AcceleratedSha256.digest(CanonicalBytes(ByteArray(0))).lowercaseHex,
        )
    }

    /**
     * The reason ADR 0058 exists, guarded as a **ratio between two measurements taken in the same
     * run on the same machine** — the shape `BasemapRouteDerivationCostTest` already uses, and for
     * its reason: an absolute figure calibrated here is a release blocker on a slower machine.
     *
     * Observed when this landed: **113x** on Kotlin/Native (macOS arm64, debug) and **7.2x** on the
     * JVM, which at the 167-tile frame `CLAUDE.md` records is 538 ms of hashing becoming 4.8 ms, and
     * 61 ms becoming 8.4 ms. The threshold is 2x, leaving more than three times headroom under the
     * smaller of the two, because what this must catch is the platform path silently falling back —
     * not a machine having a bad day.
     *
     * Skipped where there is no platform digest to reach, which is Linux by design.
     */
    @Test
    fun theAcceleratedDigestIsActuallyFasterWhereThePlatformHasOne() {
        val tile = ByteArray(64 * 1024) { (it * 31).toByte() }
        if (platformSha256(tile) == null) return

        val canonical = CanonicalBytes(tile)
        repeat(30) { PureKotlinSha256.digest(canonical); AcceleratedSha256.digest(canonical) }

        val rounds = 100
        val pure = measureTime { repeat(rounds) { PureKotlinSha256.digest(canonical) } }
        val accelerated = measureTime { repeat(rounds) { AcceleratedSha256.digest(canonical) } }

        val speedup = pure.inWholeMicroseconds.toDouble() /
            accelerated.inWholeMicroseconds.toDouble().coerceAtLeast(1.0)
        assertTrue(speedup >= 2.0, "the platform digest is not being reached: ${speedup}x")
    }

    @Test
    fun theNoCopyAccessorSeesTheSameBytesAsTheCopyingOne() {
        // byteSnapshot is what makes the platform path worth taking (ADR 0058), and it is the one
        // place CanonicalBytes hands out its own array. If the two ever disagreed, every digest taken
        // through the fast path would be of something other than what the type claims to hold.
        val bytes = ByteArray(300) { (it * 13).toByte() }
        val canonical = CanonicalBytes(bytes)

        assertContentEquals(canonical.bytes, canonical.byteSnapshot)
        assertEquals(canonical.size, canonical.byteSnapshot.size)
    }
}
