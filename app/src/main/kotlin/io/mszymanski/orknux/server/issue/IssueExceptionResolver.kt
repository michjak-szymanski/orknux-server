package io.mszymanski.orknux.server.issue

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * The tracker's refusals, in words the page can put in front of somebody.
 *
 * Beside the code that raises them, the way the memory's and the agent's are.
 * The tracker's older refusals are still answered by the workflow resolver,
 * which grew to hold most of the server's - a resolver returns null for what it
 * does not recognise and the next one is asked, so the two sit side by side
 * without either having to know about the other.
 *
 * An address the server will not keep is the caller's to fix and says why, so
 * it is a bad request rather than a failure: somebody who typed a bare hostname
 * needs to be told that, not told that something went wrong.
 */
@Component
class IssueExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is IssueLinkInvalidException,
            is IssueLinkNotYoursException,
            is IssueObserverInvalidException,
            /*
             * A refused move is the caller's to fix and says how, which is what
             * makes it a bad request rather than a failure: an administrator
             * told the assignee is in the way can change it and press the
             * button again.
             */
            is IssueMoveRefusedException,
            /*
             * The three ways of linking two issues badly are all the caller's to
             * fix and each names what is wrong: itself, somewhere else, or
             * already linked some other way.
             */
            is IssueRelationToItselfException,
            is IssueRelationElsewhereException,
            is IssueRelationAlreadyException,
            -> ErrorType.BAD_REQUEST

            is IssueLinkNotFoundException,
            is IssueRelationNotFoundException,
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
