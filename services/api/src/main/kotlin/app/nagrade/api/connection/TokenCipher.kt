package app.nagrade.api.connection

import org.springframework.stereotype.Component

// Seam for #11 (AES-GCM, ADR 0002): everything that reads or writes tokens in the database goes through here
interface TokenCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

// TEMPORARY until #11: stores tokens as plain text. Only acceptable on a local database
@Component
class PlainTextTokenCipher : TokenCipher {
    override fun encrypt(plainText: String) = plainText
    override fun decrypt(cipherText: String) = cipherText
}
