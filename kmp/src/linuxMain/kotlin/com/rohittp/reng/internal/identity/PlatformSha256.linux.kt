package com.rohittp.reng.internal.identity

/**
 * Linux has no digest RenG can reach without linking OpenSSL, and RenG links nothing (ADR 0058), so
 * this target keeps the block loop it has always used.
 */
internal actual fun platformSha256(bytes: ByteArray): ByteArray? = null
