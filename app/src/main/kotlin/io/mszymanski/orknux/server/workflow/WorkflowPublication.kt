package io.mszymanski.orknux.server.workflow

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * A workflow as it was when somebody published it.
 *
 * Until this existed, "published" was a word on a screen: a trigger fired every
 * workflow with a node instancing it and the runner read the rows as they were
 * at that moment, so an event arriving mid-edit ran the half-drawn graph. The
 * badge said Draft and the graph ran anyway.
 *
 * Now publishing takes a copy and the copy is what runs. What is left in the
 * tables is the draft, which is what makes saving safe - a graph can be left
 * half-finished overnight without answering the next event with it.
 *
 * The graph is kept as the execution module reads it, serialised whole. A
 * second set of tables shadowing the first would be a second schema to migrate
 * every time a node gains a field, in exchange for queries nobody makes: this
 * is read entire, by one caller, at the moment a run begins.
 */
@Entity
@Table(name = "workflow_publication")
class WorkflowPublication(
    @Id
    @Column(name = "workflow_id", nullable = false)
    val workflowId: Long,

    @Column(name = "published_at", nullable = false)
    var publishedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "published_by", nullable = false, length = 120)
    var publishedBy: String = "system",

    /** The runnable graph as JSON: nodes, their bindings, edges and branches. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var graph: String = "{}",
)

interface WorkflowPublicationRepository : JpaRepository<WorkflowPublication, Long>
