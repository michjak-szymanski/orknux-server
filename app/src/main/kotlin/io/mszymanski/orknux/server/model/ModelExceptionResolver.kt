package io.mszymanski.orknux.server.model

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.connector.model.ModelDiscoveryFailedException
import io.mszymanski.orknux.connector.model.ModelIdInvalidException
import io.mszymanski.orknux.connector.model.ModelNameInvalidException
import io.mszymanski.orknux.connector.model.ModelNameTakenException
import io.mszymanski.orknux.connector.model.ModelProviderCredentialAmbiguousException
import io.mszymanski.orknux.connector.model.ModelProviderEndpointInvalidException
import io.mszymanski.orknux.connector.model.ModelProviderNameInvalidException
import io.mszymanski.orknux.connector.model.ModelProviderNameTakenException
import io.mszymanski.orknux.connector.model.ModelProviderVariableNotFoundException
import io.mszymanski.orknux.connector.model.ModelProviderVariableNotSecretException
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component
import io.mszymanski.orknux.connector.model.ModelNotFoundException as ModuleModelNotFound
import io.mszymanski.orknux.connector.model.ModelProviderNotFoundException as ModuleProviderNotFound

/**
 * What the model side of the connection module refuses is the caller's to fix:
 * a duplicate name is a bad request wherever it was noticed.
 */
@Component
class ModelExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is ModelProviderNameTakenException,
            is ModelProviderNameInvalidException,
            is ModelProviderEndpointInvalidException,
            is ModelProviderCredentialAmbiguousException,
            is ModelProviderVariableNotSecretException,
            is ModelNameTakenException,
            is ModelNameInvalidException,
            is ModelIdInvalidException,
            -> ErrorType.BAD_REQUEST

            // The provider would not answer. Nothing the caller sent is wrong,
            // and nothing here is missing: the other end is unavailable.
            is ModelDiscoveryFailedException -> ErrorType.INTERNAL_ERROR

            is ModelProviderNotFoundException,
            is ModelNotFoundException,
            is ModuleProviderNotFound,
            is ModuleModelNotFound,
            // A variable in another workspace answers the same as one that is
            // nothing, so guessing at ids is not a way to learn what is there.
            is ModelProviderVariableNotFoundException,
            -> ErrorType.NOT_FOUND

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
