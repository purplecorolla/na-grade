package app.nagrade.api.connection

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Encryption of provider tokens at rest (ADR 0002). Applied by EncryptedTokenConverter on the entity columns
interface TokenCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

class TokenDecryptionException(cause: Throwable? = null) : RuntimeException("stored token could not be decrypted", cause)

// AES-256-GCM: confidentiality plus integrity (a changed byte fails the tag check instead of decrypting to garbage).
// Stored as "v1:" + base64(IV || ciphertext || tag). The version prefix leaves room to rotate the key later:
// a "v2:" with a new key can be read side by side with "v1:" while the rows are re-encrypted
@Component
class AesGcmTokenCipher(@Value("\${app.token-encryption-key}") base64Key: String) : TokenCipher {

    private val key = SecretKeySpec(decodeKey(base64Key), "AES")
    private val random = SecureRandom()

    override fun encrypt(plainText: String): String {
        // A fresh IV per token: reusing an IV with the same key breaks GCM
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        return PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)))
    }

    override fun decrypt(cipherText: String): String {
        if (!cipherText.startsWith(PREFIX)) throw TokenDecryptionException()
        // Never put the stored value in the message: it would end up in the logs
        return try {
            val bytes = Base64.getDecoder().decode(cipherText.removePrefix(PREFIX))
            val cipher = Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES)) }
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            throw TokenDecryptionException(e)
        }
    }

    private companion object {
        const val PREFIX = "v1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BYTES = 32

        fun decodeKey(base64Key: String): ByteArray {
            val bytes = runCatching { Base64.getDecoder().decode(base64Key.trim()) }.getOrNull()
            require(bytes != null && bytes.size == KEY_BYTES) {
                "TOKEN_ENCRYPTION_KEY must be $KEY_BYTES bytes in base64 (generate one with: openssl rand -base64 32)"
            }
            return bytes
        }
    }
}
