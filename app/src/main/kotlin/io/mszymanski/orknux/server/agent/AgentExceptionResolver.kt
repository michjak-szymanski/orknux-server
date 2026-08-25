package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.action.ImportCycleException
import io.mszymanski.orknux.server.action.ImportNameInvalidException
import io.mszymanski.orknux.server.action.ImportNameTakenException
import io.mszymanski.orknux.server.action.ImportNotEditableException
import io.mszymanski.orknux.server.action.ImportNotFoundException
import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

@Component
class AgentExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is AgentInUseException,
            is AgentNameTakenException,
            is AgentNameInvalidException,
            is ToolNameTakenException,
            is ToolNameInvalidException,
            is ToolSourceInvalidException,
            is ToolCodeIncompleteException,
            is ToolParamInvalidException,
            is ToolParamDuplicateException,
            is ToolObjectRequiredException,
            is SkillNameTakenException,
            is SkillNameInvalidException,
            is SkillContentInvalidException,
            is SkillCatalogNameTakenException,
            is SkillCatalogNameInvalidException,
            is SkillCatalogInUseException,
            is ToolInUseException,
            is ImportNameInvalidException,
            is ImportNameTakenException,
            is ImportNotEditableException,
            is AgentModelUnusableException,
            is AgentMemoryShareUnusableException,
            -> ErrorType.BAD_REQUEST

            is AgentNotFoundException,
            is ImportNotFoundException,
            is ToolNotFoundException,
            is SkillNotFoundException,
            is SkillCatalogNotFoundException,
            -> ErrorType.NOT_FOUND

            else -> return null
        }

        return refused(exception, errorType, environment)
    }
}
