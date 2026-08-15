package io.mszymanski.gyloli.connector.connection

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

/** Kept in sync with the defaults declared on the paged queries in the schema. */
private const val DEFAULT_PAGE_SIZE = 20
private const val MAX_PAGE_SIZE = 100

/** Builds a page request from caller-supplied arguments, clamped to a sane range. */
fun pageRequest(page: Int?, size: Int?, sort: Sort): PageRequest =
    PageRequest.of(
        (page ?: 0).coerceAtLeast(0),
        (size ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE),
        sort,
    )
