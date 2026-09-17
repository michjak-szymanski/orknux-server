package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * How long one run of a tool or a function may hold its thread.
 *
 * Three scopes, asked in order: the tool's or function's own number, the
 * workspace's default, and — where neither has said anything — null, which the
 * sandbox reads as the installation's bound. One place, because four callers
 * need the same answer and a timeout resolved differently in one of them is a
 * script that runs longer in a workflow than it did in its test.
 *
 * Read per call rather than cached, so changing a setting changes the next run
 * and never one already going.
 */
@Service
class ScriptTimeouts(private val workspaces: WorkspaceRepository) {

    /** The bound for one run, in milliseconds, or null for the installation's. */
    fun millisFor(ownSeconds: Int?, workspaceId: Long?): Long? {
        val seconds = ownSeconds
            ?: workspaceId?.let { workspaces.findByIdOrNull(it)?.scriptTimeoutSeconds }
        return seconds?.let { it * 1000L }
    }
}
