package io.mszymanski.gyloli.server.workflow

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.gyloli.server.action.ActionFailedException
import io.mszymanski.gyloli.server.action.ActionNameInvalidException
import io.mszymanski.gyloli.server.action.ActionNameTakenException
import io.mszymanski.gyloli.server.action.ActionNotFoundException
import io.mszymanski.gyloli.server.action.ActionSettingMissingException
import io.mszymanski.gyloli.server.action.ActionSubtypeMismatchException
import io.mszymanski.gyloli.server.action.FunctionInUseException
import io.mszymanski.gyloli.server.action.FunctionNameInvalidException
import io.mszymanski.gyloli.server.action.FunctionNameTakenException
import io.mszymanski.gyloli.server.action.FunctionNotFoundException
import io.mszymanski.gyloli.server.action.FunctionParamInvalidException
import io.mszymanski.gyloli.server.action.FunctionSourceInvalidException
import io.mszymanski.gyloli.server.condition.ConditionCheckMismatchException
import io.mszymanski.gyloli.server.condition.ConditionCycleException
import io.mszymanski.gyloli.server.condition.ConditionFunctionNotBooleanException
import io.mszymanski.gyloli.server.condition.ConditionFunctionRequiredException
import io.mszymanski.gyloli.server.condition.ConditionInUseException
import io.mszymanski.gyloli.server.condition.ConditionMembersRequiredException
import io.mszymanski.gyloli.server.condition.ConditionNameInvalidException
import io.mszymanski.gyloli.server.condition.ConditionNameTakenException
import io.mszymanski.gyloli.server.condition.ConditionNotFoundException
import io.mszymanski.gyloli.server.condition.ConditionPropertyMismatchException
import io.mszymanski.gyloli.server.condition.ConditionValuesRequiredException
import io.mszymanski.gyloli.server.trigger.TriggerConnectionRequiredException
import io.mszymanski.gyloli.server.trigger.TriggerNameInvalidException
import io.mszymanski.gyloli.server.trigger.TriggerNameTakenException
import io.mszymanski.gyloli.server.trigger.TriggerNotFoundException
import io.mszymanski.gyloli.server.trigger.TriggerPayloadInvalidException
import io.mszymanski.gyloli.server.trigger.TriggerScheduleInvalidException
import io.mszymanski.gyloli.server.trigger.TriggerScheduleRequiredException
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/** Same reasoning as the team resolver: rejections need a message a UI can show. */
@Component
class WorkflowExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            // Raised by the execution module while a run is being planned.
            is io.mszymanski.gyloli.workflow.execution.WorkflowGraphEmptyException,
            is io.mszymanski.gyloli.workflow.execution.WorkflowGraphCyclicException,
            -> ErrorType.BAD_REQUEST

            is io.mszymanski.gyloli.workflow.execution.WorkflowNotFoundException,
            is io.mszymanski.gyloli.workflow.execution.ExecutionNotFoundException,
            is ExecutionNotFoundException,
            -> ErrorType.NOT_FOUND

            is WorkflowNameTakenException,
            is WorkflowNameInvalidException,
            is WorkflowGraphEmptyException,
            is WorkflowNotAssignedException,
            is TriggerNameTakenException,
            is TriggerNameInvalidException,
            is TriggerConnectionRequiredException,
            is TriggerScheduleRequiredException,
            is TriggerScheduleInvalidException,
            is TriggerPayloadInvalidException,
            is TriggerNotInCatalogueException,
            is ActionNotInCatalogueException,
            is ActionNameTakenException,
            is ActionNameInvalidException,
            is ActionSettingMissingException,
            is ActionSubtypeMismatchException,
            is ActionFailedException,
            is FunctionNameTakenException,
            is FunctionNameInvalidException,
            is FunctionParamInvalidException,
            is FunctionSourceInvalidException,
            is FunctionInUseException,
            is ConditionNotInCatalogueException,
            is GraphInvalidException,
            is ConditionNameTakenException,
            is ConditionNameInvalidException,
            is ConditionPropertyMismatchException,
            is ConditionCheckMismatchException,
            is ConditionValuesRequiredException,
            is ConditionMembersRequiredException,
            is ConditionCycleException,
            is ConditionInUseException,
            is ConditionFunctionRequiredException,
            is ConditionFunctionNotBooleanException,
            -> ErrorType.BAD_REQUEST

            is TriggerNotFoundException -> ErrorType.NOT_FOUND
            is ActionNotFoundException, is FunctionNotFoundException -> ErrorType.NOT_FOUND
            is ConditionNotFoundException -> ErrorType.NOT_FOUND
            is WorkflowNotFoundException -> ErrorType.NOT_FOUND
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
