package io.mszymanski.orknux.connector.security

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.springframework.stereotype.Component

/**
 * How wide a column holding an encrypted credential has to be.
 *
 * The envelope is base64, so it is about a third larger than the bytes it
 * carries, and it also holds a 12-byte initialisation vector, a 16-byte
 * authentication tag and a version prefix. A 1000-character credential lands
 * near 1400; this leaves room for one several times longer, which JWT-shaped
 * credentials can be.
 */
const val SECRET_COLUMN_LENGTH = 4000

/**
 * Puts [SecretCipher] between a credential field and its column.
 *
 * A converter rather than encrypting at each call site: there is no code path
 * that can forget it, and nothing above the entity has to know the value is
 * encrypted at all. Adding a new credential field is one annotation.
 *
 * Hibernate resolves this through Spring's bean container, which is how it gets
 * the cipher.
 */
@Converter
@Component
class SecretConverter(private val cipher: SecretCipher) : AttributeConverter<String?, String?> {

    override fun convertToDatabaseColumn(attribute: String?): String? = cipher.encrypt(attribute)

    override fun convertToEntityAttribute(dbData: String?): String? = cipher.decrypt(dbData)
}
