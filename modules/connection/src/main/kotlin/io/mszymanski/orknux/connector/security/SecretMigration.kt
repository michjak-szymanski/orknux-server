package io.mszymanski.orknux.connector.security

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

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
 * cannot come to disagree the way a hand-kept list did.
 *
 * Reading is safe throughout. [SecretCipher.decrypt] passes anything without the
 * version prefix through untouched, so an installation keeps working between
 * the upgrade and this finishing, and a row half-way through is not a state that
 * exists: each row is one update, in a transaction of its own.
 *
 * Idempotent by construction — it only selects rows that are not already sealed,
 * so on every later boot it finds nothing and says nothing. A row that is
 * already in an envelope is never selected, and [SecretCipher.encrypt] would
 * hand it back unchanged even if it were.
 *
 * Nothing here is allowed to stop the server. This runs on `ApplicationReadyEvent`
 * and Spring Boot answers a listener that throws on that event by closing the
 * context, so a failure in here is not a failed migration, it is an installation
 * that does not come up — and the doctor page that would explain why is served by
 * the server that did not start.
 */
@Component
class SecretMigration(
    private val jdbc: JdbcTemplate,
    private val cipher: SecretCipher,
    private val columns: SecretColumns,
    transactions: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * One row, one transaction, and never one the caller opened.
     *
     * The method used to be `@Transactional`, which made four columns times every
     * row of the database a single transaction — the opposite of what the
     * paragraph above it claimed — so one row that could not be written rolled
     * back every row that could. `REQUIRES_NEW` for the reason it is used
     * everywhere else here: a row's write must not be able to reach a
     * transaction it did not open, whoever called this and whatever they are in
     * the middle of.
     */
    private val perRow = TransactionTemplate(transactions).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @EventListener(ApplicationReadyEvent::class)
    fun encryptStoredSecrets() {
        val status = cipher.keyStatus()
        if (status != SecretCipher.KeyStatus.Usable) {
            reportNoKey(status)
            return
        }

        var rewritten = 0
        var failed = 0
        for (column in columns.all) {
            val outcome = rewrite(column)
            rewritten += outcome.first
            failed += outcome.second
        }

        if (rewritten > 0) {
            log.info("Encrypted {} stored credential(s) that were kept as plain text", rewritten)
        }
        if (failed > 0) {
            log.warn(
                "{} stored credential(s) could not be encrypted and are still in plain text. " +
                    "The rest were. See the doctor page for which columns.",
                failed,
            )
        }
    }

    /**
     * The upgrade that arrives without a key.
     *
     * `encrypt` reaches a `by lazy { check(...) }`, so with no key the first
     * plaintext row threw `IllegalStateException` straight out of the ready
     * event and the installation did not start. Every other part of this
     * codebase answers a missing key by carrying on and saying so —
     * [SecretCipher.decrypt] was written specifically never to throw, and its
     * comment explains why — so this does too.
     *
     * Said out loud only when there is something to say. An installation with no
     * key and nothing in the clear has nothing wrong with this; the doctor's
     * "Secret key" card is where a missing key is reported, and a WARN on every
     * boot for a condition with no consequence is a WARN people learn to skip.
     */
    private fun reportNoKey(status: SecretCipher.KeyStatus) {
        val waiting = columns.all.sumOf { plaintextCount(it) }
        if (waiting == 0L) return

        log.warn(
            "{} stored credential(s) are in plain text and were left that way: the secret key is {}. " +
                "They stay readable and the server runs, but anyone who can read this database or a " +
                "backup of it can read them. Set orknux.security.secret-key (openssl rand -base64 32) " +
                "and they are encrypted on the next start.",
            waiting,
            when (status) {
                SecretCipher.KeyStatus.Missing -> "not set"
                SecretCipher.KeyStatus.NotBase64 -> "not valid base64"
                is SecretCipher.KeyStatus.WrongLength -> "${status.bytes} bytes; AES-256 needs 32"
                SecretCipher.KeyStatus.Usable -> "usable"
            },
        )
    }

    /** How many values in one column have never been through the cipher. */
    private fun plaintextCount(column: SecretColumns.SecretColumn): Long = runCatching {
        jdbc.queryForObject(
            "SELECT count(*) FROM ${column.table} WHERE ${column.column} IS NOT NULL " +
                "AND ${column.column} <> '' AND ${column.column} NOT LIKE '$PREFIX%'",
            Long::class.java,
        )
    }.getOrNull() ?: 0L

    /**
     * Reads each unsealed value and writes it back sealed.
     *
     * Done column by column with the plaintext never leaving this method, and
     * never logged: the whole point is that it stops being readable.
     *
     * Contained per row. One credential that cannot be written costs only
     * itself: it stays as it was, the ones already rewritten stay rewritten, and
     * the sweep carries on to the next. What went wrong is logged by table and
     * column and by nothing else — the value is the one thing that must not
     * reach a log file.
     *
     * @return how many were rewritten, and how many could not be.
     */
    private fun rewrite(column: SecretColumns.SecretColumn): Pair<Int, Int> {
        val rows = runCatching {
            jdbc.queryForList(
                "SELECT ${column.id} AS id, ${column.column} AS value FROM ${column.table} " +
                    "WHERE ${column.column} IS NOT NULL AND ${column.column} NOT LIKE '$PREFIX%'",
            )
        }.getOrElse { failure ->
            log.warn("Could not read {} to encrypt it: {}", column, failure.javaClass.simpleName)
            return 0 to 0
        }

        var rewritten = 0
        var failed = 0
        for (row in rows) {
            val id = (row["id"] as Number).toLong()
            val plaintext = row["value"] as? String ?: continue

            runCatching {
                perRow.executeWithoutResult {
                    jdbc.update(
                        "UPDATE ${column.table} SET ${column.column} = ? WHERE ${column.id} = ?",
                        cipher.encrypt(plaintext),
                        id,
                    )
                }
            }.onSuccess { rewritten++ }.onFailure { failure ->
                failed++
                log.warn("Could not encrypt {} of row {}: {}", column, id, failure.javaClass.simpleName)
            }
        }

        return rewritten to failed
    }

    private companion object {
        const val PREFIX = "orkx1:"
    }
}
