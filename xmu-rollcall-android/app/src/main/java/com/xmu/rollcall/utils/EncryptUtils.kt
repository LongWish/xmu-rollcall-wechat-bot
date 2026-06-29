package com.xmu.rollcall.utils

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptUtils {
    private const val AES_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
    private val random = SecureRandom()

    fun generateRandomString(length: Int): String {
        return (1..length)
            .map { AES_CHARS[random.nextInt(AES_CHARS.length)] }
            .joinToString("")
    }

    /**
     * Encrypts the password using AES-CBC with a randomly generated 64-character prefix
     * and a randomly generated 16-character IV. Key is derived from the salt.
     *
     * Equivalent to Python's:
     *   plaintext = random_string(64) + password
     *   key = salt.encode()
     *   iv = random_string(16).encode()
     */
    fun encryptPassword(password: String, salt: String): String {
        val plaintext = generateRandomString(64) + password
        val keyBytes = salt.toByteArray(Charsets.UTF_8)
        val ivString = generateRandomString(16)
        val ivBytes = ivString.toByteArray(Charsets.UTF_8)

        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return Base64.getEncoder().encodeToString(encryptedBytes)
    }
}
