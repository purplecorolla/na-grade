package app.nagrade.api.connection

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

class AesGcmTokenCipherTest {

    private fun fakeKey(fill: Char) = Base64.getEncoder().encodeToString(fill.toString().repeat(32).toByteArray())

    private val cipher = AesGcmTokenCipher(fakeKey('a'))

    @Test
    fun `decrypt returns the original token`() {
        val encrypted = cipher.encrypt("fake-access-token")

        assertThat(encrypted).startsWith("v1:").doesNotContain("fake-access-token")
        assertThat(cipher.decrypt(encrypted)).isEqualTo("fake-access-token")
    }

    @Test
    fun `the same token encrypts differently every time`() {
        assertThat(cipher.encrypt("fake-access-token")).isNotEqualTo(cipher.encrypt("fake-access-token"))
    }

    @Test
    fun `a tampered value fails instead of decrypting to garbage`() {
        val bytes = Base64.getDecoder().decode(cipher.encrypt("fake-access-token").removePrefix("v1:"))
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()

        assertThatThrownBy { cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(bytes)) }
            .isInstanceOf(TokenDecryptionException::class.java)
    }

    @Test
    fun `another key can't decrypt`() {
        val encrypted = cipher.encrypt("fake-access-token")

        assertThatThrownBy { AesGcmTokenCipher(fakeKey('b')).decrypt(encrypted) }
            .isInstanceOf(TokenDecryptionException::class.java)
    }

    @Test
    fun `plain text or an unknown version is rejected`() {
        for (stored in listOf("fake-access-token", "v9:AAAA", "v1:not-base64!")) {
            assertThatThrownBy { cipher.decrypt(stored) }.isInstanceOf(TokenDecryptionException::class.java)
        }
    }

    @Test
    fun `a key that is not 32 bytes in base64 is refused with a hint and without echoing it`() {
        for (key in listOf("", "not-base64!", Base64.getEncoder().encodeToString(ByteArray(16)))) {
            assertThatThrownBy { AesGcmTokenCipher(key) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("openssl rand -base64 32")
                .satisfies({ assertThat(it.message).doesNotContain(key.ifEmpty { "\u0000" }) })
        }
    }
}
