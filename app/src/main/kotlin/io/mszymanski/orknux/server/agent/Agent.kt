package io.mszymanski.orknux.server.agent

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table

enum class AgentType {
    REACT,
    LLM,
}

/** An AI agent configured by a workspace. */
@Entity
@Table(name = "agent")
class Agent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val workspaceId: Long,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var type: AgentType,

    @Column(length = 500)
    var description: String? = null,

    @Column(name = "system_prompt", columnDefinition = "text")
    var systemPrompt: String? = null,

    @Column(nullable = false)
    var enabled: Boolean = true,

    /**
     * The model this agent thinks with.
     *
     * Null is none chosen. Nothing is substituted for it: which model an agent
     * uses changes what it costs and what it answers, and that is the
     * workspace's decision rather than one to make on its behalf.
     */
    @Column(name = "model_id")
    var modelId: Long? = null,

    /** MCP servers this agent may connect to, in the order they were added. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_mcp_server", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false)
    var mcpServers: MutableList<String> = mutableListOf(),

    /**
     * Memory catalogs this agent may read, by name.
     *
     * By name rather than by id, the same way the MCP servers are: an agent is
     * configured against what the workspace calls things, and the list is a
     * grant — what an agent may look in, not everything the workspace knows.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_memory_catalog", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false)
    var memoryCatalogs: MutableList<String> = mutableListOf(),

    /**
     * Which skill catalogs this agent may draw on.
     *
     * The same kind of grant as [memoryCatalogs], and by name for the same
     * reason. Granted per catalog rather than per skill: what an agent is
     * expected to know is a decision worth making once, not once per skill.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_skill_catalog", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false)
    var skillCatalogs: MutableList<String> = mutableListOf(),

    /**
     * Which of the workspace's tools this agent may call.
     *
     * The same grant as the rest, and the strictest of them in effect: a skill
     * is a page an agent reads, and a tool is code that does something. An agent
     * granted none calls none.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_granted_tool", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false)
    var tools: MutableList<String> = mutableListOf(),
)
