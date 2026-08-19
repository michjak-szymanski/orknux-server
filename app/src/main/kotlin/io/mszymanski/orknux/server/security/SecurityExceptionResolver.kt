package io.mszymanski.orknux.server.security

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * What the GraphQL side answers when access says no.
 *
 * A workspace the caller cannot see is answered as one that is not there,
 * which is what the REST side has always done with the same exception and what
 * the message now says. Needing the administrator role is a different answer
 * and stays forbidden: it says nothing about what exists, only about who is
 * asking.
 */
@Component
class SecurityExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is WorkspaceForbiddenException -> ErrorType.NOT_FOUND
            is AdminRequiredException -> ErrorType.FORBIDDEN
            else -> return null
        }

        return GraphQLError.newError()
            .errorType(errorType)
            .message(exception.message)
            .path(environment.executionStepInfo.path)
            .location(environment.field.sourceLocation)
            .build()
    }
}
