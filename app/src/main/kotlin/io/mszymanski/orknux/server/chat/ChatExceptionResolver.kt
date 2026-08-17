package io.mszymanski.orknux.server.chat

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
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
            is ChatDisabledException,
            -> ErrorType.BAD_REQUEST

            is ChatSessionNotFoundException -> ErrorType.NOT_FOUND

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
