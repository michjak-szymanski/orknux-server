package io.mszymanski.gyloli.server.integration

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.gyloli.connector.connection.ConnectionNameInvalidException
import io.mszymanski.gyloli.connector.connection.ConnectionNameTakenException
import io.mszymanski.gyloli.connector.connection.ConnectionNotConfiguredException
import io.mszymanski.gyloli.connector.connection.ConnectionUrlInvalidException
import io.mszymanski.gyloli.connector.connection.McpServerAddressInvalidException
import io.mszymanski.gyloli.connector.connection.McpServerNameInvalidException
import io.mszymanski.gyloli.connector.connection.McpServerNameTakenException
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component
import io.mszymanski.gyloli.connector.connection.ConnectionNotFoundException as ModuleConnectionNotFound
import io.mszymanski.gyloli.connector.connection.McpServerNotFoundException as ModuleMcpServerNotFound

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
