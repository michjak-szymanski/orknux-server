package io.mszymanski.orknux.server.agent

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AgentRepository : JpaRepository<Agent, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<Agent>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): Agent?

    /** However the name was typed: a model asking for an agent has read it, not copied it. */
    @Query("select a from Agent a where a.workspaceId = :workspaceId and lower(a.name) = lower(:name)")
    fun findNamed(@Param("workspaceId") workspaceId: Long, @Param("name") name: String): Agent?

    /**
     * Which of the workspace's agents were granted this tool.
     *
     * Exactly as spelled, not however it was typed, because that is how the
     * grant is read: [WorkspaceToolCaller] looks the name up with
     * `findByWorkspaceIdAndName`, so a grant that differs by a letter's case is
     * already a grant that resolves to nothing and there is nothing here to
     * protect.
     */
    @Query("select a from Agent a join a.tools t where a.workspaceId = :workspaceId and t = :name")
    fun findGrantedTool(@Param("workspaceId") workspaceId: Long, @Param("name") name: String): List<Agent>

    /** Which of the workspace's agents were granted this skill catalog, spelled the same way. */
    @Query("select a from Agent a join a.skillCatalogs c where a.workspaceId = :workspaceId and c = :name")
    fun findGrantedSkillCatalog(@Param("workspaceId") workspaceId: Long, @Param("name") name: String): List<Agent>

    /**
     * Which of the workspace's agents were granted this memory catalog, spelled
     * the same way.
     *
     * Exactly as spelled for the reason the tool grant is: `MemoryTool` keeps
     * the catalogs whose `name` is `in` the granted set, so a grant differing by
     * a letter's case already reads nothing and there is nothing here to protect.
     */
    @Query("select a from Agent a join a.memoryCatalogs c where a.workspaceId = :workspaceId and c = :name")
    fun findGrantedMemoryCatalog(@Param("workspaceId") workspaceId: Long, @Param("name") name: String): List<Agent>
}
