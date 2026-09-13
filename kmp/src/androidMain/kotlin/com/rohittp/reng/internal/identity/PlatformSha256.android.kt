package com.rohittp.reng.internal.identity

import java.security.MessageDigest

/**
 * `MessageDigest` reaches the ARMv8 SHA-2 extensions on every Android device that has them, which is
 * every 64-bit device RenG supports. A fresh instance per call because `MessageDigest` is stateful
 * and RenG hashes from whatever thread a preparation resumed on.
 */
internal actual fun platformSha256(bytes: ByteArray): ByteArray? =
    MessageDigest.getInstance("SHA-256").digest(bytes)
