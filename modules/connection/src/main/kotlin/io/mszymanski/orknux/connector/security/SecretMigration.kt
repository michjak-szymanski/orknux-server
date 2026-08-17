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
 * through the entity, where [SecretConverter] encrypts them on the way out.
 *
 * Reading is safe throughout. [SecretCipher.decrypt] passes anything without the
 * version prefix through untouched, so an installation keeps working between
 * the upgrade and this finishing, and a row half-way through is not a state that
 * exists: each row is one update.
 *
 * Idempotent by construction — it only selects rows that are not already sealed,
 * so on every later boot it finds nothing and says nothing.
 */
@Component
class SecretMigration(
    private val jdbc: JdbcTemplate,
    private val cipher: SecretCipher,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun encryptStoredSecrets() {
        val rewritten = COLUMNS.sumOf { (table, column) -> rewrite(table, column) }

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
    private fun rewrite(table: String, column: String): Int {
        val rows = jdbc.queryForList(
            "SELECT id, $column AS value FROM $table WHERE $column IS NOT NULL AND $column NOT LIKE '$PREFIX%'",
        )

        for (row in rows) {
            val id = row["id"] as Number
            val plaintext = row["value"] as String
            jdbc.update("UPDATE $table SET $column = ? WHERE id = ?", cipher.encrypt(plaintext), id.toLong())
        }

        return rows.size
    }

    private companion object {
        const val PREFIX = "orkx1:"

        /** Every column holding a credential. A new one belongs here as well as on its entity. */
        val COLUMNS = listOf(
            "model_provider" to "secret",
            "workspace_connection" to "secret",
            "workspace_connection" to "app_token",
            "mcp_server" to "secret",
        )
    }
}
