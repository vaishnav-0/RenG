package com.rohittp.reng.internal.identity

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/**
 * CommonCrypto's `CC_SHA256`, which reaches the ARMv8 SHA-2 extensions on every Apple target RenG
 * ships. Duplicated between `iosMain` and `macosMain` rather than shared: this project has no Apple
 * source set, and adding one edits `kmp/build.gradle.kts`, whose token fingerprint the repository
 * policy pins. Sixteen identical lines are the cheaper of the two.
 *
 * The output is a `UByteArray` because that is the buffer type `CC_SHA256` declares; an empty input
 * has no address to pin, so it answers `null` and takes the pure-Kotlin path.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)
internal actual fun platformSha256(bytes: ByteArray): ByteArray? {
    if (bytes.isEmpty()) return null
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { input ->
        digest.usePinned { output ->
            CC_SHA256(input.addressOf(0), bytes.size.toUInt(), output.addressOf(0))
        }
    }
    return digest.toByteArray()
}
