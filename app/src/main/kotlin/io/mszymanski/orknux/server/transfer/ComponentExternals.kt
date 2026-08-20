package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * The only place in this package that opens a row holding a credential.
 *
 * Three of the things an envelope points at are kept beside a key, a token or a
 * header, and the connector module keeps them that way on purpose — credentials
 * are read in one place. This is the transfer package's one door onto them, and
 * it is deliberately narrow: everything below reads a *name* and a *type* off a
 * row and hands back nothing else, so there is no path from an envelope to a
 * secret to find rather than to reason about.
 *
 * It answers in both directions, because both directions ask the same question
 * about the same three tables. The export asks what a row is called, so the
 * file can name it; the import asks which row a name means here, and whether an
 * id somebody bound is this workspace's to bind.
 */
@Service
class ComponentExternals(
    private val connections: WorkspaceConnectionRepository,
    private val mcpServers: McpServerRepository,
    private val providers: ModelProviderRepository,
    private val models: LlmModelRepository,
) {

    /**
     * The model an agent thinks with, as a provider's name and the model's.
     *
     * Null when the agent names no model, or names one this workspace no longer
     * has — a dangling id is not something to carry, and an agent without a
     * model is a shape the catalogue already allows.
     */
    fun modelReference(workspaceId: Long, modelId: Long?): ExternalReference? {
        val model = modelId?.let { models.findByIdOrNull(it) } ?: return null
        val provider = providers.findByIdOrNull(model.providerId)?.takeIf { it.workspaceId == workspaceId }
            ?: return null
        return ExternalReference(ExternalKind.MODEL, model.name, provider.name, provider.type.name)
    }

    /** The connection an action sends through or a trigger listens on, by name and type. */
    fun connectionReference(workspaceId: Long, connectionId: Long?): ExternalReference? {
        val connection = connectionId?.let { connections.findByIdOrNull(it) }
            ?.takeIf { it.workspaceId == workspaceId } ?: return null
        return ExternalReference(ExternalKind.CONNECTION, connection.name, type = connection.type.name)
    }

    /**
     * An MCP server an agent may connect to.
     *
     * The agent already holds the name rather than an id, so nothing is read to
     * build this. It is still a reference and still needs binding: a name that
     * means a server in one workspace means nothing in another, and an agent
     * granted a server that is not there would be an agent with a tool it can
     * never call and no sign of why.
     */
    fun mcpServerReference(name: String): ExternalReference = ExternalReference(ExternalKind.MCP_SERVER, name)

    /**
     * Which row this reference means in the target workspace, if any.
     *
     * By name, and by the provider's name as well for a model — two providers
     * offering a model of the same name is common enough that matching on the
     * model alone would point an agent at whichever was found first.
     */
    fun find(workspaceId: Long, reference: ExternalReference): Long? = when (reference.kind) {
        ExternalKind.MODEL -> {
            val provider = reference.provider?.let { providers.findByWorkspaceIdAndName(workspaceId, it) }
            provider?.let { models.findByProviderIdAndName(requireNotNull(it.id), reference.name)?.id }
        }

        ExternalKind.CONNECTION -> connections.findByWorkspaceIdAndName(workspaceId, reference.name)?.id

        ExternalKind.MCP_SERVER -> mcpServers.findByWorkspaceIdAndName(workspaceId, reference.name)?.id
    }

    /**
     * What one of this workspace's rows is called, for an id somebody bound.
     *
     * Null is the answer to every id that is not this workspace's, whether it
     * belongs to another workspace or to nothing at all — one answer to both, so
     * that guessing at ids is not a way to learn what another workspace holds.
     *
     * The label rather than the bare name, which differ only for a model. That
     * is also what an agent writes back into itself for an MCP server, and
     * correctly: an MCP server has no provider, so its label is its name.
     */
    fun labelOf(workspaceId: Long, kind: ExternalKind, id: Long): String? = when (kind) {
        ExternalKind.MODEL -> models.findByIdOrNull(id)?.let { model ->
            providers.findByIdOrNull(model.providerId)
                ?.takeIf { it.workspaceId == workspaceId }
                ?.let { ExternalReference(kind, model.name, it.name).label }
        }

        ExternalKind.CONNECTION ->
            connections.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId }?.name

        ExternalKind.MCP_SERVER ->
            mcpServers.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId }?.name
    }
}
