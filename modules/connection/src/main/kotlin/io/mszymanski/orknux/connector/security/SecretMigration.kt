package io.mszymanski.orknux.connector.security

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Rewrites credentials that predate encryption.
 *
 * Flyway cannot do this: sealing a value needs the key, and the key is the one
 * thing the database is not given. So the rows are found in SQL and rewritten
 * through [SecretCipher], which is the same envelope [SecretConverter] would
 * have put them in had they been written after it existed.
 *
 * Which columns is not a decision made here. [SecretColumns] reads it off the
 * entities, so a field that is encrypted is a field that is swept, and the two
 * cannot come to disagree the way a hand-kept list did — it named four of the
 * eight, and `shell.private_key` was one of the four it did not.
 *
 * Reading is safe throughout. [SecretCipher.decrypt] passes anything without the
 * version prefix through untouched, so an installation keeps working between
 * the upgrade and this finishing.
 *
 * Idempotent by construction — it only selects rows that are not already sealed,
 * so on every later boot it finds nothing and says nothing. A row already in an
 * envelope is never selected, and [SecretCipher.encrypt] would hand it back
 * unchanged even if it were.
 */
@Component
class SecretMigration(
    private val jdbc: JdbcTemplate,
    private val cipher: SecretCipher,
    private val columns: SecretColumns,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun encryptStoredSecrets() {
        val rewritten = columns.all.sumOf { rewrite(it) }

        if (rewritten > 0) {
            log.info("Encrypted {} stored credential(s) that were kept as plain text", rewritten)
        }
    }

    /**
     * Reads each unsealed value and writes it back sealed.
     *
     * Done column by column with the plaintext never leaving this method, and
     * never logged: the whole point is that it stops being readable.
     */
    private fun rewrite(column: SecretColumns.SecretColumn): Int {
        val rows = jdbc.queryForList(
            "SELECT ${column.id} AS id, ${column.column} AS value FROM ${column.table} " +
                "WHERE ${column.column} IS NOT NULL AND ${column.column} NOT LIKE '$PREFIX%'",
        )

        for (row in rows) {
            val id = row["id"] as Number
            val plaintext = row["value"] as String
            jdbc.update(
                "UPDATE ${column.table} SET ${column.column} = ? WHERE ${column.id} = ?",
                cipher.encrypt(plaintext),
                id.toLong(),
            )
        }

        return rows.size
    }

    private companion object {
        const val PREFIX = "orkx1:"
    }
}
