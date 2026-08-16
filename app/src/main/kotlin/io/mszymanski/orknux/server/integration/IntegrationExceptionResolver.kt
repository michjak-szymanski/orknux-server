package io.mszymanski.orknux.server.integration

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.connector.connection.ConnectionNameInvalidException
import io.mszymanski.orknux.connector.connection.ConnectionNameTakenException
import io.mszymanski.orknux.connector.connection.ConnectionNotConfiguredException
import io.mszymanski.orknux.connector.connection.ConnectionUrlInvalidException
import io.mszymanski.orknux.connector.connection.McpServerAddressInvalidException
import io.mszymanski.orknux.connector.connection.McpServerNameInvalidException
import io.mszymanski.orknux.connector.connection.McpServerNameTakenException
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component
import io.mszymanski.orknux.connector.connection.ConnectionNotFoundException as ModuleConnectionNotFound
import io.mszymanski.orknux.connector.connection.McpServerNotFoundException as ModuleMcpServerNotFound

/**
 * What the connection module refuses is the caller's to fix: a duplicate name is
 * a bad request wherever it was noticed.
 */
@Component
class IntegrationExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is ConnectionNameTakenException,
            is ConnectionNameInvalidException,
            is ConnectionUrlInvalidException,
            is ConnectionNotConfiguredException,
            is McpServerNameTakenException,
            is McpServerNameInvalidException,
            is McpServerAddressInvalidException,
            -> ErrorType.BAD_REQUEST

            is ConnectionNotFoundException,
            is McpServerNotFoundException,
            is ModuleConnectionNotFound,
            is ModuleMcpServerNotFound,
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
