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

/**
 * There is one kind of agent.
 *
 * REACT was the other one, and the distinction never earned its place: every
 * agent reaches the model the same way and every agent may call tools. What an
 * agent is allowed to call is configured per agent, which is the setting that
 * was actually doing the work all along.
 *
 * The enum is kept rather than the column dropped so that the shape of an agent
 * does not change for one withdrawn value.
 */
enum class AgentType {
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

    /**
     * Whether this agent may ask orknux about orknux.
     *
     * The built-in server, which is not one of [mcpServers] and never appears
     * among them: those are addresses somebody registered, and this one is the
     * application the agent is already running inside. A boolean rather than a
     * name in that list, because there is no server to name.
     *
     * Granted, it can also start workflows — which is the point, and worth
     * knowing before granting it: an agent that starts a workflow which asks an
     * agent is a loop nothing here breaks.
     */
    @Column(name = "orknux_access", nullable = false)
    var orknuxAccess: Boolean = false,

    /**
     * Whether this agent may open a shell on one of the installation's machines
     * and run commands there.
     *
     * Plural and unnamed, which is the owner's design and the right one: from
     * where an agent sits the question is "can I run a command somewhere", not
     * "may I run one on build-box-3". Naming a machine here would make every
     * agent's configuration stale the day that machine is replaced, and would
     * put a decision about infrastructure in a workspace's settings when the
     * shells themselves are installation-wide and an administrator's.
     *
     * Which shell a session lands on is decided at the moment it opens; see
     * `ShellService.choose` for the rule and why it is that rule.
     *
     * Worth knowing before granting it: what contains this is the machine on
     * the other end of the SSH connection, and nothing in this application. An
     * agent given this can run any command the account on that machine can.
     */
    @Column(name = "shell_access", nullable = false)
    var shellAccess: Boolean = false,

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

    /**
     * Which icon a node drawn from this starts with.
     *
     * A seed, not a rule: the node owns its icon once it has one, the same way
     * it owns the parameters this seeded. Null draws whatever the kind draws.
     */
    @Column(length = 40)
    var icon: String? = null,
)
