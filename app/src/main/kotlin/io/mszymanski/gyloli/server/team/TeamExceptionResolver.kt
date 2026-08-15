package io.mszymanski.gyloli.server.team

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * Without this, a rejected team change reaches the client as a generic
 * INTERNAL_ERROR, which is not something a UI can show a user.
 */
@Component
class TeamExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is TeamNameTakenException, is TeamNameInvalidException -> ErrorType.BAD_REQUEST
            is TeamNotFoundException -> ErrorType.NOT_FOUND
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
