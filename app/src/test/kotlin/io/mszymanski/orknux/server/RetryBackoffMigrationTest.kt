package io.mszymanski.orknux.server

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/**
 * What a graph drawn before the backoff was a curve does when the migration runs.
 *
 * The whole promise of V182 is one line of SQL: EXPONENTIAL becomes a multiplier
 * of two, and null stays null. It is a promise about rows nobody in this suite
 * writes, in a column the code can no longer name, so nothing else here can
 * check it - a test against the schema as it is now cannot say a word about a
 * node written under the schema as it was.
 *
 * So this one migrates to the version before, writes the rows an installation
 * running 0.9.0 actually has, and migrates the rest of the way. Its own database
 * because it is the only test that has to see the schema at two versions, and
 * the suite's own container is already at the last one before the first context
 * is built.
 */
class RetryBackoffMigrationTest {

    /** The last version before the curve became a number. */
    private val beforeTheCurve = "181"

    @Test
    fun `a node that doubled goes on doubling, and one that did not is left alone`() {
        PostgreSQLContainer("postgres:18")
            .withDatabaseName("orknux")
            .withUsername("orknux")
            .withPassword("orknux")
            .use { postgres ->
                postgres.start()

                migrate(postgres, target = beforeTheCurve)
                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { db ->
                    writeTheOldRows(db)
                }

                migrate(postgres, target = "latest")

                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { db ->
                    val multipliers = readMultipliers(db)

                    // The claim, in two numbers: what EXPONENTIAL said, said as
                    // the multiplier that says the same thing.
                    assertThat(multipliers["doubling"]).isEqualTo(2.0)
                    // FIXED was the wait repeated, and no multiplier is the wait
                    // repeated - so it is stored as no multiplier rather than as
                    // a 1 that means the same and reads as an edit.
                    assertThat(multipliers["flat"]).isNull()
                    // And the node from before there was a word for the curve at
                    // all, which is most of them.
                    assertThat(multipliers["silent"]).isNull()

                    // Nothing else about the policy moved: the attempts and the
                    // wait are the two numbers a run actually spends.
                    assertThat(attemptsAndWait(db, "doubling")).isEqualTo(4 to 30)
                    // And nothing was invented where the node had said nothing.
                    assertThat(theRest(db, "doubling")).containsOnlyNulls()
                }
            }
    }

    private fun migrate(postgres: PostgreSQLContainer<*>, target: String) {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration/postgresql")
            .target(target)
            .load()
            .migrate()
    }

    /**
     * Three nodes as an installation on 0.9.0 has them: one doubling, one told
     * outright that it did not, and one that never said.
     */
    private fun writeTheOldRows(db: Connection) {
        db.createStatement().use { sql ->
            sql.execute("INSERT INTO workflow (id, name) VALUES (900, 'Answer the customer')")
            for ((key, curve) in listOf("doubling" to "'EXPONENTIAL'", "flat" to "'FIXED'", "silent" to "NULL")) {
                sql.execute(
                    """
                    INSERT INTO workflow_node
                        (workflow_id, node_key, kind, name, position_x, position_y,
                         retry_attempts, retry_backoff_seconds, retry_backoff)
                    VALUES (900, '$key', 'ACTION', '$key', 0, 0, 4, 30, $curve)
                    """,
                )
            }
        }
    }

    private fun readMultipliers(db: Connection): Map<String, Double?> = db
        .createStatement()
        .use { sql ->
            sql.executeQuery("SELECT node_key, retry_multiplier FROM workflow_node WHERE workflow_id = 900").use {
                buildMap {
                    while (it.next()) {
                        val key = it.getString("node_key")
                        val held = it.getDouble("retry_multiplier")
                        // Read before anything else touches the row: wasNull is
                        // about the last column got, not about the one named.
                        put(key, if (it.wasNull()) null else held)
                    }
                }
            }
        }

    private fun attemptsAndWait(db: Connection, key: String): Pair<Int, Int> = db.createStatement().use { sql ->
        sql.executeQuery(
            "SELECT retry_attempts, retry_backoff_seconds FROM workflow_node " +
                "WHERE workflow_id = 900 AND node_key = '$key'",
        ).use {
            it.next()
            it.getInt("retry_attempts") to it.getInt("retry_backoff_seconds")
        }
    }

    /** The three fields that did not exist when this row was written. */
    private fun theRest(db: Connection, key: String): List<Any?> = db.createStatement().use { sql ->
        sql.executeQuery(
            "SELECT retry_max_wait_seconds, retry_jitter, retry_budget_seconds FROM workflow_node " +
                "WHERE workflow_id = 900 AND node_key = '$key'",
        ).use {
            it.next()
            listOf(it.getObject(1), it.getObject(2), it.getObject(3))
        }
    }
}
