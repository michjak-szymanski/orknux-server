package io.mszymanski.orknux.server.security

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.connector.security.SecretCredentialAmbiguousException
import io.mszymanski.orknux.connector.security.SecretVariableNotFoundException
import io.mszymanski.orknux.connector.security.SecretVariableNotSecretException
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * What binding a secret field to a workspace variable refuses.
 *
 * Its own resolver rather than a copy in each aggregate's, because the rule
 * belongs to no aggregate: a model provider's key, a Slack connection's two
 * tokens, an MCP server's credential and whatever grows a secret next are all
 * refused by the same three sentences. A copy per controller is three chances
 * for one of them to answer INTERNAL_ERROR where the others answer BAD_REQUEST,
 * which is a caller told the server broke when what happened is that they sent
 * two credentials for one field.
 */
@Component
class SecretExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is SecretCredentialAmbiguousException,
            is SecretVariableNotSecretException,
            -> ErrorType.BAD_REQUEST

            // A variable in another workspace answers the same as one that is
            // nothing, so guessing at ids is not a way to learn what is there.
            is SecretVariableNotFoundException -> ErrorType.NOT_FOUND

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
