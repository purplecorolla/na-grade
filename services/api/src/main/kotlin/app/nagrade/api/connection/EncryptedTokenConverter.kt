package app.nagrade.api.connection

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

// On the column itself, so no code path can write a token without encrypting it. Hibernate gets this instance
// from Spring (SpringBeanContainer), which injects the cipher
@Converter
class EncryptedTokenConverter(private val cipher: TokenCipher) : AttributeConverter<String, String> {
    override fun convertToDatabaseColumn(attribute: String?): String? = attribute?.let(cipher::encrypt)
    override fun convertToEntityAttribute(dbData: String?): String? = dbData?.let(cipher::decrypt)
}
