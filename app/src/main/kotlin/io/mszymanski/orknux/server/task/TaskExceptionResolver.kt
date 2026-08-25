package io.mszymanski.orknux.server.task

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * A task's refusals, said in the words the screen shows.
 *
 * Beside the code that raises them, the way the tracker's and the agent's are.
 * Everything here is what was asked for rather than what went wrong: a task
 * whose agent has been deleted, a request two people pressed at once, a prompt
 * with nothing in it.
 */
@Component
class TaskExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is TaskNotFoundException, is TaskRequestNotFoundException -> ErrorType.NOT_FOUND

            /*
             * Somebody else got there first. BAD_REQUEST rather than a conflict
             * type, which this schema has no vocabulary for - what matters is
             * that the second person is told the task has moved on rather than
             * left believing they approved it.
             */
            is TaskRequestSettledException,
            is TaskPromptMissingException,
            is TaskWorkerMissingException,
            is TaskNotRunnableException,
            -> ErrorType.BAD_REQUEST

            else -> return null
        }

        return refused(exception, errorType, environment)
    }
}
