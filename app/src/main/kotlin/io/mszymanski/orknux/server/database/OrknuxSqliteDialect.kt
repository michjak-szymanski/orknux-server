package io.mszymanski.orknux.server.database

import org.hibernate.community.dialect.SQLiteDialect
import org.hibernate.type.SqlTypes

/**
 * Hibernate's SQLite dialect, with one thing said differently.
 *
 * SQLite has a single integer type. A column declared INTEGER already holds
 * eight bytes, and BIGINT is not a second type but another spelling of the same
 * one - so nothing is narrowed by preferring the first spelling. What the two
 * are not equally good for is the primary key: SQLite only fills in a key it
 * has been given by itself when the column is declared exactly INTEGER, and a
 * key declared BIGINT is accepted, never filled in, and left null on every row
 * inserted. That is the failure this exists to avoid, and it is a quiet one.
 *
 * So every mapped Long is INTEGER here, keys and the columns pointing at them
 * alike. Saying it in the dialect rather than only in the schema is what keeps
 * `ddl-auto: validate` meaningful: Hibernate compares what it would have written
 * against what is there, and both sides now say the same word.
 *
 * The dialect underneath is a community one - Hibernate ships it outside the
 * core and does not run its own CI against it - which is worth remembering when
 * something is odd here and correct on Postgres.
 */
class OrknuxSqliteDialect : SQLiteDialect() {

    override fun columnType(sqlTypeCode: Int): String =
        if (sqlTypeCode == SqlTypes.BIGINT) "integer" else super.columnType(sqlTypeCode)
}
