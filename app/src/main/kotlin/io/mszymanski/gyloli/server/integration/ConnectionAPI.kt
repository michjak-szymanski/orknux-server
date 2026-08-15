package io.mszymanski.gyloli.server.integration

import io.mszymanski.gyloli.connector.connection.ConnectionInput
import io.mszymanski.gyloli.connector.connection.ConnectionPage
import io.mszymanski.gyloli.connector.connection.ConnectionService
import io.mszymanski.gyloli.connector.connection.ConnectionView
import io.mszymanski.gyloli.server.security.TeamAccess
import io.mszymanski.gyloli.server.team.TeamAuditCategory
import io.mszymanski.gyloli.server.team.TeamAuditRecorder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * The organization's default connections. The connection module holds them and
 * knows nothing about who is asking; this decides that, and records what was
 * done — which only the part of the platform with users can do.
 */
@Controller
class ConnectionAPI(
    private val connections: ConnectionService,
    private val access: TeamAccess,
    private val auditRecorder: TeamAuditRecorder,
) {

    @QueryMapping
    fun connections(@Argument page: Int?, @Argument size: Int?): ConnectionPage {
        access.requireAdmin()
        return connections.connections(page, size)
    }

    @QueryMapping
    fun connection(@Argument id: Long): ConnectionView? {
        access.requireAdmin()
        return connections.connection(id)
    }

    @MutationMapping
    fun createConnection(@Argument input: ConnectionInput): ConnectionView {
        access.requireAdmin()
        val created = connections.createConnection(input)

        val name = created.connection.name
        auditRecorder.record(null, TeamAuditCategory.INTEGRATION, "Default connection $name created")
        if (created.addedToTeams > 0) {
            val teams = if (created.addedToTeams == 1) "team" else "teams"
            auditRecorder.record(
                null,
                TeamAuditCategory.INTEGRATION,
                "Default connection $name added to ${created.addedToTeams} existing $teams",
            )
        }
        return created.connection
    }

    /** Name, type and URL are shared, so the change also reaches the teams holding a copy. */
    @MutationMapping
    fun updateConnection(@Argument id: Long, @Argument input: ConnectionInput): ConnectionView {
        access.requireAdmin()
        val previousName = connections.connection(id)?.name
        val updated = connections.updateConnection(id, input)

        val message = if (previousName == null || previousName == updated.name) {
            "Default connection ${updated.name} updated"
        } else {
            "Default connection $previousName renamed to ${updated.name}"
        }
        auditRecorder.record(null, TeamAuditCategory.INTEGRATION, message)
        return updated
    }

    /**
     * Removes the default. The copies teams already hold stay where they are,
     * credentials included, and become the teams' own.
     */
    @MutationMapping
    fun deleteConnection(@Argument id: Long): Boolean {
        access.requireAdmin()
        val name = connections.connection(id)?.name ?: return false
        if (!connections.deleteConnection(id)) return false

        auditRecorder.record(null, TeamAuditCategory.INTEGRATION, "Default connection $name deleted")
        return true
    }
}
