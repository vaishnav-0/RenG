package com.rohittp.reng.internal.identity

/**
 * The platform's own SHA-256 over [bytes], or `null` where this target has none to reach (ADR 0058).
 *
 * `null` is an ordinary answer rather than a failure: it is what Linux returns, because reaching a
 * hardware digest there means linking OpenSSL and RenG links nothing, and it is what every target did
 * before this existed.
 *
 * [bytes] is read and never retained or mutated by any implementation.
 */
internal expect fun platformSha256(bytes: ByteArray): ByteArray?

/**
 * The digest RenG uses everywhere: the platform's where there is one, the block loop otherwise.
 *
 * The two are required to agree byte for byte — a SHA-256 is an identity here and not only a check,
 * so an implementation that disagreed would split a content-addressed cache across platforms rather
 * than fail. `PlatformSha256EquivalenceTest` asserts it on every target.
 */
internal object AcceleratedSha256 : Sha256Function {
    override fun digest(bytes: CanonicalBytes): Sha256Digest {
        val platform = platformSha256(bytes.byteSnapshot)
        return if (platform == null) PureKotlinSha256.digest(bytes) else Sha256Digest(platform)
    }
}
