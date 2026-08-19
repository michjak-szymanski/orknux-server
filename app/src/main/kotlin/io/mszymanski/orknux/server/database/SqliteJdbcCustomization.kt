package io.mszymanski.orknux.server.database

import com.github.kagkarlsson.scheduler.jdbc.DefaultJdbcCustomization

/**
 * What db-scheduler needs told about SQLite, since it does not ship a dialect
 * for it.
 *
 * It recognises the database by name and then falls back to its default, which
 * writes the standard `OFFSET 0 ROWS FETCH FIRST n ROWS ONLY` when it asks what
 * is due. SQLite has never had that spelling and refuses the statement outright,
 * so the poll fails a second after the scheduler starts and goes on failing once
 * a second afterwards - the log fills up and no schedule ever fires. LIMIT is
 * the same clause in the words SQLite uses.
 *
 * Timestamps are written in UTC rather than with a zone, which is what the
 * default warns about on any database whose columns cannot carry one. SQLite's
 * cannot: it has no zoned type at all, so a zone written into one is a zone read
 * back as whatever the reader assumed. Fixing on UTC means both ends assume the
 * same thing.
 */
class SqliteJdbcCustomization : DefaultJdbcCustomization(true) {

    override fun getName(): String = "SQLite"

    override fun getQueryLimitPart(limit: Int): String = " LIMIT $limit"
}
