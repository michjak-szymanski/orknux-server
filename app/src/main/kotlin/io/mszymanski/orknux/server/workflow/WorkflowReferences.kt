package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.workflow.execution.GraphNode
import io.mszymanski.orknux.workflow.execution.GraphVersion
import org.springframework.stereotype.Component

/**
 * Which of a workspace's workflows name a definition, and in which copy.
 *
 * A definition is deleted from a list that says nothing about workflows, so the
 * only thing between a delete and a workflow that stops working is a question
 * asked here. The question has two halves, and the second is the one that
 * matters:
 *
 * - The **draft** graph names it, and a draft can be redrawn. Somebody who wants
 *   the definition gone can open the canvas and take the node off.
 * - The **published** copy names it, and that copy cannot be edited at all. It
 *   was taken when somebody pressed Publish, nothing cascades into it, and the
 *   only ways it stops naming something are publishing over it or taking the
 *   workflow out of the workspace. Until then it is what a trigger runs.
 *
 * A guard that looked only at draft nodes would let exactly the bad case
 * through: the node taken off the canvas, never republished, and a published run
 * still holding an id that resolves to nothing.
 *
 * Only workflows this workspace has assigned are asked, and that is not
 * tidiness - it is the way out. A workflow removed from the workspace can no
 * longer be edited or published from it, so counting it would be a refusal with
 * nothing anybody could do about it.
 *
 * The published half is asked through [AppWorkflowGraphSource] rather than by
 * reading the publication row, because the question is "what would a run
 * resolve", not "what is in that table". The two differ for a workflow that was
 * live before snapshots existed - it has a status and no snapshot, and what runs
 * is the draft - and the door that answers it is the one the runner uses, so
 * they cannot drift apart.
 *
 * What the runnable graph carries is the whole of the published half: it keeps
 * `agentId`, `actionId` and `conditionId` and nothing else, so those three are
 * the only references a published run resolves. A trigger id is not among them,
 * which is why the trigger question below is a draft question and says so.
 */
@Component
class WorkflowReferences(
    private val assignments: WorkspaceWorkflowRepository,
    private val nodes: WorkflowNodeRepository,
    private val graphs: AppWorkflowGraphSource,
) {

    /** Which of the workspace's workflows run this action. */
    fun toAction(workspaceId: Long, actionId: Long): List<String> =
        using(workspaceId, { it.actionId == actionId }, { it.actionId == actionId })

    /** Which of the workspace's workflows instance this agent. */
    fun toAgent(workspaceId: Long, agentId: Long): List<String> =
        using(workspaceId, { it.agentId == agentId }, { it.agentId == agentId })

    /** Which of the workspace's workflows ask this condition. */
    fun toCondition(workspaceId: Long, conditionId: Long): List<String> =
        using(workspaceId, { it.conditionId == conditionId }, { it.conditionId == conditionId })

    /**
     * Which of the workspace's workflows start from this trigger.
     *
     * The draft alone, because that is where the answer is: publishing does not
     * copy a trigger id, and what an arriving event looks for is the trigger
     * *node* in the drawn graph. A published workflow whose trigger is deleted
     * does not fail in the middle of a run - it stops being reached at all,
     * which is the quieter half of the same bug.
     */
    fun toTrigger(workspaceId: Long, triggerId: Long): List<String> =
        using(workspaceId, { it.triggerId == triggerId }, { false })

    /**
     * The workflows naming it, said the way a refusal has to say them.
     *
     * The published copy is reported in preference to the draft where one
     * workflow holds both, because it is the harder of the two to be rid of:
     * redrawing the canvas is not enough, and somebody told only "the workflow
     * Answer" would do exactly that and be refused again.
     */
    private fun using(
        workspaceId: Long,
        inDraft: (WorkflowNode) -> Boolean,
        inPublished: (GraphNode) -> Boolean,
    ): List<String> = assignments.findByWorkspaceId(workspaceId)
        .map { it.workflow }
        .distinctBy { it.id }
        .mapNotNull { workflow ->
            val workflowId = workflow.id ?: return@mapNotNull null
            when {
                published(workspaceId, workflowId).any(inPublished) -> "the published workflow ${workflow.name}"
                nodes.findByWorkflowId(workflowId).any(inDraft) -> "the workflow ${workflow.name}"
                else -> null
            }
        }

    /**
     * The nodes a run of the published copy would resolve, or none.
     *
     * Asked in two steps, and the first is not an optimisation. A workflow that
     * has never been published answers by raising, and raising out of a
     * transactional method that has joined this delete's transaction marks that
     * transaction rollback-only - catching it afterwards does not unmark it, so
     * the delete would fail on commit having refused nothing. The question of
     * whether there is anything published is therefore asked as a question,
     * which is what [AppWorkflowGraphSource.published] is for, and the graph is
     * only fetched once the answer is yes.
     *
     * Nothing is caught here. Everything that would have thrown ordinarily has
     * been asked first - the workflow is one this workspace has assigned, so it
     * is not missing either - and swallowing what is left would mean deleting
     * against a transaction already doomed.
     */
    private fun published(workspaceId: Long, workflowId: Long): List<GraphNode> {
        if (!graphs.published(workflowId)) return emptyList()
        return graphs.graph(workspaceId, workflowId, GraphVersion.PUBLISHED).nodes
    }
}
