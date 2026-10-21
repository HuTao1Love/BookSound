package com.zyagodin.booksound.server.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Password hashing and random tokens. */
object Secrets {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    // Argon2id, OWASP's second recommended setting: 19 MiB, 2 passes.
    private const val MEMORY_KIB = 19 * 1024
    private const val ITERATIONS = 2
    private const val PARALLELISM = 1
    private const val HASH_BYTES = 32

    fun hashPassword(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = argon2(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM)
        return "argon2id\$v=19\$m=$MEMORY_KIB,t=$ITERATIONS,p=$PARALLELISM\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    fun verifyPassword(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 5 || parts[0] != "argon2id") return false
        val params = parts[2].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
        val salt = decoder.decode(parts[3])
        val expected = decoder.decode(parts[4])
        val actual = argon2(password, salt, params.getValue("m"), params.getValue("t"), params.getValue("p"))
        return MessageDigest.isEqual(expected, actual)
    }

    /** Runs a hash of a fixed password so a login for an unknown user takes as long as for a known one. */
    fun burnPasswordCheck(password: String) {
        argon2(password, ByteArray(16), MEMORY_KIB, ITERATIONS, PARALLELISM)
    }

    private fun argon2(password: String, salt: ByteArray, memoryKib: Int, iterations: Int, parallelism: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        val out = ByteArray(HASH_BYTES)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(password.toCharArray(), out)
        return out
    }

    /** A random 256-bit token, URL-safe. */
    fun token(): String = encoder.encodeToString(ByteArray(32).also(random::nextBytes))

    /** Tokens are stored only as hashes, so a copy of the database grants no access. */
    fun tokenHash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
