package com.xmu.rollcall.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptUtilsTest {

    @Test
    fun testGenerateRandomString() {
        val str1 = EncryptUtils.generateRandomString(10)
        val str2 = EncryptUtils.generateRandomString(16)
        assertEquals(10, str1.length)
        assertEquals(16, str2.length)
    }

    @Test
    fun testEncryptPassword() {
        val password = "my_secret_password_123"
        val salt = "1234567890abcdef" // 16 bytes salt
        
        val encryptedBase64 = EncryptUtils.encryptPassword(password, salt)
        assertNotNull(encryptedBase64)
        assertTrue(encryptedBase64.isNotEmpty())

        // Decode the base64 string
        val ciphertextBytes = Base64.getDecoder().decode(encryptedBase64)
        
        // Decrypt using a dummy IV of all zeros
        // In AES-CBC, if you decrypt with a wrong IV, only the first 16 bytes (block 1) will be corrupted.
        // Since the plaintext starts with 64 bytes of random characters (4 full blocks),
        // the password (which starts at byte 64 / block 5) will decrypt perfectly fine
        // because block 5 is decrypted using the ciphertext of block 4, not the IV.
        val dummyIv = ByteArray(16) { 0 }
        
        val keySpec = SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(dummyIv)
        
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decryptedBytes = cipher.doFinal(ciphertextBytes)
        
        val decryptedText = String(decryptedBytes, Charsets.UTF_8)
        
        // The password should be at the end of the decrypted string
        assertTrue(decryptedText.endsWith(password))
        
        // The length of decrypted text should be 64 (prefix) + password length
        assertEquals(64 + password.length, decryptedText.length)
    }
}
