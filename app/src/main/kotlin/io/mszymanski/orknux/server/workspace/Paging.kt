package io.mszymanski.orknux.server.workspace

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort

/** Kept in sync with the defaults declared on the paged queries in the schema. */
private const val DEFAULT_PAGE_SIZE = 20
private const val MAX_PAGE_SIZE = 100

/** The slice of an already-filtered list that belongs on the requested page. */
fun <T> List<T>.page(pageable: Pageable): List<T> {
    val from = pageable.offset.toInt()
    if (from >= size) return emptyList()
    return subList(from, minOf(from + pageable.pageSize, size))
}


/**
 * A column somebody pressed, turned into an order the database can take.
 *
 * Every list on every table screen is asked to be sortable by any of its
 * columns - issue #358 - and there are two dozen of them. Written out per list
 * that would be two dozen enums in the schema and two dozen near-identical
 * `when` blocks; written like this it is one line per list saying which of its
 * columns map to which stored fields.
 *
 * [columns] is the allowlist and it is the whole of the safety: what arrives
 * from a client is a key into it and never a property name, so nothing a caller
 * types can reach a field the list does not draw - or a field at all. Anything
 * unrecognised falls back to [fallback], because a list that refuses to load
 * over an order nobody can see is worse than a list in its usual order.
 *
 * A column can map to more than one field, for the two cases that need it: a
 * tie somebody would notice (equal names, ordered by id so the page does not
 * shuffle between reads) and a column drawn from a pair.
 */
fun sortBy(
    order: String?,
    ascending: Boolean?,
    columns: Map<String, List<String>>,
    fallback: String,
    fallbackAscending: Boolean = true,
): Sort {
    val asked = order?.trim()?.uppercase()?.takeIf { it in columns }
    val fields = columns[asked] ?: columns.getValue(fallback)
    // The direction a caller gave, or the one this list is read in when nobody
    // has said: names A to Z, and anything with a clock on it newest first.
    val up = ascending ?: if (asked == null || asked == fallback) fallbackAscending else true
    return Sort.by(if (up) Sort.Direction.ASC else Sort.Direction.DESC, *fields.toTypedArray())
}

/** Builds a page request from client-supplied arguments, clamped to a sane range. */
fun pageRequest(page: Int?, size: Int?, sort: Sort): PageRequest =
    PageRequest.of(
        (page ?: 0).coerceAtLeast(0),
        (size ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE),
        sort,
    )
