package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * How long one run of a tool or a function may hold its thread.
 *
 * Three scopes, asked in order: the tool's or function's own number, the
 * workspace's default for that kind of run, and — where neither has said
 * anything — null, which the sandbox reads as the installation's bound. One
 * place, because five callers need the same answer and a timeout resolved
 * differently in one of them is a script that runs longer in a workflow than
 * it did in its test.
 *
 * ## Why a tool and a function are asked separately
 *
 * They were one number, and the number suited neither. A function runs where
 * nobody is waiting in particular: a workflow step, a condition, a webhook. A
 * tool runs with a model stopped mid-turn until it answers and, in a chat, a
 * person watching that happen. Twenty seconds is patience in one and a failure
 * in the other, so the workspace holds two settings and this asks for the one
 * the run is.
 *
 * Read per call rather than cached, so changing a setting changes the next run
 * and never one already going.
 */
@Service
class ScriptTimeouts(private val workspaces: WorkspaceRepository) {

    /**
     * The bound for one function run, in milliseconds, or null for the
     * installation's. A workflow's step, a condition, a webhook's answer.
     */
    fun forFunction(ownSeconds: Int?, workspaceId: Long?): Long? =
        millis(ownSeconds, workspaceId) { it.functionTimeoutSeconds }

    /**
     * The bound for one tool call an agent made. Its own because the wait is
     * a model's, and a person's - see the note above.
     */
    fun forTool(ownSeconds: Int?, workspaceId: Long?): Long? =
        millis(ownSeconds, workspaceId) { it.toolTimeoutSeconds }

    private fun millis(ownSeconds: Int?, workspaceId: Long?, chosen: (Workspace) -> Int?): Long? {
        val seconds = ownSeconds ?: workspaceId?.let { workspaces.findByIdOrNull(it)?.let(chosen) }
        return seconds?.let { it * 1000L }
    }
}
