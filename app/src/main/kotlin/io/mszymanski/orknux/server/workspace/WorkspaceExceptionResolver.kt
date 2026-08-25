package io.mszymanski.orknux.server.workspace

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
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
            // The Chat card's four model pickers, each of which will take only
            // one kind. Unmapped these arrived as INTERNAL_ERROR - a sentence
            // written to be read by whoever picked the wrong model, delivered as
            // "the server broke" and with no code for the interface to translate.
            is ModelNotTranscriptionException,
            is ModelNotSpeechException,
            is ModelNotImageException,
            is ModelNotChatException,
            -> ErrorType.BAD_REQUEST
            is WorkspaceNotFoundException,
            is ModelNotFoundForWorkspaceException,
            -> ErrorType.NOT_FOUND
            else -> return null
        }

        return refused(exception, errorType, environment)
    }
}
