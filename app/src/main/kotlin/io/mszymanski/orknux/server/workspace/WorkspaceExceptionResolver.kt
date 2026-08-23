package io.mszymanski.orknux.server.workspace

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * Without this, a rejected workspace change reaches the client as a generic
 * INTERNAL_ERROR, which is not something a UI can show a user.
 */
@Component
class WorkspaceExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is WorkspaceNameTakenException,
            is WorkspaceNameInvalidException,
            // Bad request rather than forbidden: it is not that the caller may
            // not, it is that what they sent does not hold together.
            is WorkspaceAdminRoleNotAssignedException,
            is WorkspaceMemoryShareUnusableException,
            is WorkspaceVoiceTurnTakingUnusableException,
            -> ErrorType.BAD_REQUEST
            is WorkspaceNotFoundException -> ErrorType.NOT_FOUND
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
