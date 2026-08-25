package io.mszymanski.orknux.server.chat

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * A provider that would not answer is the caller's to act on — pick another
 * model, fix the key — so it is a bad request rather than a server fault.
 */
@Component
class ChatExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is ChatTitleInvalidException,
            is ChatMessageEmptyException,
            is ChatModelNotChosenException,
            is ChatModelUnusableException,
            is ChatAgentUnusableException,
            is ChatLlmSessionUnusableException,
            is ChatDisabledException,
            is ChatNothingToRegenerateException,
            // A picture that could not be drawn is the caller's to act on -
            // choose a model, turn attachments on, describe something else - and
            // it must arrive as a sentence with a code beside it. The picture is
            // the whole of the request, so an INTERNAL_ERROR here is a blank
            // screen where the answer was meant to be.
            is ChatPictureModelNotChosenException,
            is ChatPictureUnstorableException,
            is ChatPictureFailedException,
            -> ErrorType.BAD_REQUEST

            is ChatSessionNotFoundException -> ErrorType.NOT_FOUND

            else -> return null
        }

        return refused(exception, errorType, environment)
    }
}
