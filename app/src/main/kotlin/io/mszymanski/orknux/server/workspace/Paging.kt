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

/** Builds a page request from client-supplied arguments, clamped to a sane range. */
fun pageRequest(page: Int?, size: Int?, sort: Sort): PageRequest =
    PageRequest.of(
        (page ?: 0).coerceAtLeast(0),
        (size ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE),
        sort,
    )
