package io.mszymanski.orknux.server.workflow

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.action.ActionFailedException
import io.mszymanski.orknux.server.action.ActionInUseException
import io.mszymanski.orknux.server.action.ActionNameInvalidException
import io.mszymanski.orknux.server.action.ActionNameTakenException
import io.mszymanski.orknux.server.action.ActionNotFoundException
import io.mszymanski.orknux.server.action.ActionHeaderAmbiguousException
import io.mszymanski.orknux.server.action.ActionHeaderEmptyException
import io.mszymanski.orknux.server.action.ActionHeaderVariableElsewhereException
import io.mszymanski.orknux.server.action.ActionHoldsPlaceholderException
import io.mszymanski.orknux.server.action.ActionSettingMissingException
import io.mszymanski.orknux.server.action.ActionSubtypeMismatchException
import io.mszymanski.orknux.server.action.FunctionExternallyManagedException
import io.mszymanski.orknux.server.action.FunctionSignatureMismatchException
import io.mszymanski.orknux.server.action.FunctionInUseException
import io.mszymanski.orknux.server.action.FunctionNameInvalidException
import io.mszymanski.orknux.server.action.FunctionNameTakenException
import io.mszymanski.orknux.server.action.FunctionNotFoundException
import io.mszymanski.orknux.server.action.FunctionParamInvalidException
import io.mszymanski.orknux.server.action.FunctionCodeIncompleteException
import io.mszymanski.orknux.server.action.FunctionObjectRequiredException
import io.mszymanski.orknux.server.revision.ComponentRevisionNotFoundException
import io.mszymanski.orknux.server.revision.RevisionComponentGoneException
import io.mszymanski.orknux.server.revision.RevisionNotRestorableException
import io.mszymanski.orknux.server.security.RoleBuiltInException
import io.mszymanski.orknux.server.security.RoleInUseException
import io.mszymanski.orknux.workflow.execution.WorkflowNotPublishedException
import io.mszymanski.orknux.server.security.RoleNameInvalidException
import io.mszymanski.orknux.server.security.RoleNameTakenException
import io.mszymanski.orknux.server.security.RoleNotFoundException
import io.mszymanski.orknux.server.issue.IssueAssigneeInvalidException
import io.mszymanski.orknux.server.issue.IssueAssigneeKindMissingException
import io.mszymanski.orknux.server.issue.IssueCommentEmptyException
import io.mszymanski.orknux.server.issue.IssueCommentNotFoundException
import io.mszymanski.orknux.server.issue.IssueAttachmentNotFoundException
import io.mszymanski.orknux.server.issue.IssueAttachmentNotYoursException
import io.mszymanski.orknux.server.issue.IssueCommentNotYoursException
import io.mszymanski.orknux.server.issue.IssueNotFoundException
import io.mszymanski.orknux.server.issue.IssueTitleInvalidException
import io.mszymanski.orknux.server.user.PasswordNotSettableException
import io.mszymanski.orknux.server.user.EmailInvalidException
import io.mszymanski.orknux.server.user.PasswordTooShortException
import io.mszymanski.orknux.server.user.PasswordWrongException
import io.mszymanski.orknux.server.user.TokenNotFoundException
import io.mszymanski.orknux.server.user.UserExternallyManagedException
import io.mszymanski.orknux.server.user.UserNameInvalidException
import io.mszymanski.orknux.server.user.UserNameTakenException
import io.mszymanski.orknux.server.user.UserNotFoundException
import io.mszymanski.orknux.server.action.FunctionSourceInvalidException
import io.mszymanski.orknux.server.condition.ConditionCheckMismatchException
import io.mszymanski.orknux.server.condition.ConditionCycleException
import io.mszymanski.orknux.server.condition.ConditionFunctionElsewhereException
import io.mszymanski.orknux.server.condition.ConditionFunctionNotBooleanException
import io.mszymanski.orknux.server.condition.ConditionFunctionRequiredException
import io.mszymanski.orknux.server.condition.ConditionInUseException
import io.mszymanski.orknux.server.condition.ConditionMembersRequiredException
import io.mszymanski.orknux.server.condition.ConditionNameInvalidException
import io.mszymanski.orknux.server.condition.ConditionNameTakenException
import io.mszymanski.orknux.server.condition.ConditionNotFoundException
import io.mszymanski.orknux.server.condition.ConditionPropertyMismatchException
import io.mszymanski.orknux.server.condition.ConditionValuesRequiredException
import io.mszymanski.orknux.server.trigger.TriggerConnectionRequiredException
import io.mszymanski.orknux.server.trigger.TriggerActionUnsupportedException
import io.mszymanski.orknux.server.trigger.TriggerInUseException
import io.mszymanski.orknux.server.trigger.TriggerNameInvalidException
import io.mszymanski.orknux.server.trigger.TriggerNameTakenException
import io.mszymanski.orknux.server.trigger.TriggerNotFoundException
import io.mszymanski.orknux.server.trigger.TriggerPayloadInvalidException
import io.mszymanski.orknux.server.attachment.AttachmentNotFoundException
import io.mszymanski.orknux.server.attachment.AttachmentTooLargeException
import io.mszymanski.orknux.server.attachment.AttachmentsDisabledException
import io.mszymanski.orknux.server.attachment.RetentionOutOfRangeException
import io.mszymanski.orknux.server.trigger.TriggerScheduleInvalidException
import io.mszymanski.orknux.server.variable.VariableCatalogNameInvalidException
import io.mszymanski.orknux.server.variable.VariableCatalogNameTakenException
import io.mszymanski.orknux.server.variable.VariableCatalogNotEmptyException
import io.mszymanski.orknux.server.variable.VariableCatalogNotFoundException
import io.mszymanski.orknux.server.variable.VariableInUseException
import io.mszymanski.orknux.server.variable.VariableNameInvalidException
import io.mszymanski.orknux.server.variable.VariableNameTakenException
import io.mszymanski.orknux.server.variable.VariableNotFoundException
import io.mszymanski.orknux.server.trigger.TriggerWebhookPathInvalidException
import io.mszymanski.orknux.server.trigger.TriggerWebhookPathRequiredException
import io.mszymanski.orknux.server.trigger.TriggerWebhookPathTakenException
import io.mszymanski.orknux.server.trigger.TriggerWebhookAuthFunctionNotBooleanException
import io.mszymanski.orknux.server.trigger.TriggerWebhookAuthFunctionRequiredException
import io.mszymanski.orknux.server.trigger.TriggerWebhookShapeRequiredException
import io.mszymanski.orknux.server.trigger.TriggerScheduleRequiredException
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/** Same reasoning as the workspace resolver: rejections need a message a UI can show. */
@Component
class WorkflowExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            // Raised by the execution module while a run is being planned.
            is io.mszymanski.orknux.workflow.execution.WorkflowGraphEmptyException,
            is io.mszymanski.orknux.workflow.execution.WorkflowGraphCyclicException,
            // Why a run cannot honestly be started at the step somebody chose.
            // Each carries the reason as a sentence, because the panel offering
            // the button is where the answer has to be readable.
            is io.mszymanski.orknux.workflow.execution.StepNotInWorkflowException,
            is io.mszymanski.orknux.workflow.execution.StepNotInExecutionException,
            is io.mszymanski.orknux.workflow.execution.StepNeverRanException,
            is io.mszymanski.orknux.workflow.execution.BranchNotTakenException,
            is io.mszymanski.orknux.workflow.execution.BranchNotRecordedException,
            is io.mszymanski.orknux.workflow.execution.StepInputMissingException,
            is io.mszymanski.orknux.workflow.execution.ExecutionStillRunningException,
            -> ErrorType.BAD_REQUEST

            is io.mszymanski.orknux.workflow.execution.WorkflowNotFoundException,
            is io.mszymanski.orknux.workflow.execution.ExecutionNotFoundException,
            is ExecutionNotFoundException,
            -> ErrorType.NOT_FOUND

            is WorkflowNameTakenException,
            is WorkflowNameInvalidException,
            is WorkflowGraphEmptyException,
            is WorkflowNotAssignedException,
            is TriggerInUseException,
            is TriggerNameTakenException,
            is TriggerNameInvalidException,
            is TriggerActionUnsupportedException,
            is TriggerConnectionRequiredException,
            is TriggerScheduleRequiredException,
            is TriggerScheduleInvalidException,
            is TriggerWebhookPathRequiredException,
            is TriggerWebhookPathInvalidException,
            is TriggerWebhookPathTakenException,
            is TriggerWebhookShapeRequiredException,
            is TriggerWebhookAuthFunctionRequiredException,
            is TriggerWebhookAuthFunctionNotBooleanException,
            is TriggerPayloadInvalidException,
            is TriggerNotInCatalogueException,
            is ActionNotInCatalogueException,
            is AttachmentsDisabledException,
            is AttachmentTooLargeException,
            is VariableNameTakenException,
            is VariableNameInvalidException,
            is VariableInUseException,
            is VariableCatalogNameTakenException,
            is VariableCatalogNameInvalidException,
            is VariableCatalogNotEmptyException,
            is ObjectNotInCatalogueException,
            is ActionInUseException,
            is ActionNameTakenException,
            is ActionNameInvalidException,
            is ActionSettingMissingException,
            is ActionHoldsPlaceholderException,
            is ActionHeaderAmbiguousException,
            is ActionHeaderEmptyException,
            is ActionHeaderVariableElsewhereException,
            is ActionSubtypeMismatchException,
            is ActionFailedException,
            is FunctionSignatureMismatchException,
            is FunctionExternallyManagedException,
            is FunctionNameTakenException,
            is FunctionNameInvalidException,
            is FunctionParamInvalidException,
            is FunctionSourceInvalidException,
            is FunctionCodeIncompleteException,
            is FunctionObjectRequiredException,
            is RoleNameTakenException,
            is RoleNameInvalidException,
            is UserNameTakenException,
            is UserNameInvalidException,
            is IssueTitleInvalidException,
            is IssueCommentEmptyException,
            is IssueCommentNotYoursException,
            is IssueAttachmentNotYoursException,
            is IssueAssigneeInvalidException,
            is IssueAssigneeKindMissingException,
            is UserExternallyManagedException,
            is PasswordTooShortException,
            is PasswordWrongException,
            is PasswordNotSettableException,
            is EmailInvalidException,
            is RoleBuiltInException,
            is WorkflowNotPublishedException,
            is RoleInUseException,
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
            is ConditionFunctionElsewhereException,
            is ConditionFunctionNotBooleanException,
            is RetentionOutOfRangeException,
            is RevisionNotRestorableException,
            is RevisionComponentGoneException,
            -> ErrorType.BAD_REQUEST

            is AttachmentNotFoundException -> ErrorType.NOT_FOUND
            is VariableNotFoundException, is VariableCatalogNotFoundException -> ErrorType.NOT_FOUND
            is TriggerNotFoundException -> ErrorType.NOT_FOUND
            is ActionNotFoundException, is FunctionNotFoundException -> ErrorType.NOT_FOUND
            is RoleNotFoundException -> ErrorType.NOT_FOUND
            is UserNotFoundException, is TokenNotFoundException -> ErrorType.NOT_FOUND
            is IssueNotFoundException, is IssueCommentNotFoundException -> ErrorType.NOT_FOUND
            is IssueAttachmentNotFoundException -> ErrorType.NOT_FOUND
            is ConditionNotFoundException -> ErrorType.NOT_FOUND
            is WorkflowNotFoundException -> ErrorType.NOT_FOUND
            is WorkflowPublicationNotFoundException -> ErrorType.NOT_FOUND
            is ComponentRevisionNotFoundException -> ErrorType.NOT_FOUND
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
