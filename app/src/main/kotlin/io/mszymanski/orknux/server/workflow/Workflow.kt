package io.mszymanski.orknux.server.workflow

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/** A workflow definition, independent of the workspaces that use it. */
@Entity
@Table(name = "workflow")
class Workflow(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    var name: String,

    @Column(length = 500)
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: WorkflowStatus = WorkflowStatus.DRAFT,
)

/** A workflow made available to a workspace, which the workspace can enable or disable. */
@Entity
@Table(name = "workspace_workflow")
class WorkspaceWorkflow(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val workspaceId: Long,

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false)
    val workflow: Workflow,

    @Column(nullable = false)
    var enabled: Boolean = true,
)
