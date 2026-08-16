package io.mszymanski.orknux.workflow.temporal

import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import io.mszymanski.orknux.workflow.health.Reachability
import io.mszymanski.orknux.workflow.health.ServiceHealth
import io.temporal.serviceclient.WorkflowServiceStubs
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * Temporal answers the standard gRPC health check, so this asks that rather
 * than starting a workflow to find out. It is what the monitoring screen reads:
 * a run that will not start is usually Temporal being down.
 */
@Service
@ConditionalOnProperty(name = ["orknux.temporal.enabled"], havingValue = "true", matchIfMissing = true)
class TemporalHealth(private val stubs: WorkflowServiceStubs) : ServiceHealth {

    override val service: String = "temporal"

    override fun reachable(): Reachability = try {
        when (val status = stubs.healthCheck().status) {
            ServingStatus.SERVING -> Reachability(true, "Serving")
            else -> Reachability(false, "Answered $status")
        }
    } catch (failure: Exception) {
        Reachability(false, failure.message ?: "The service could not be reached")
    }
}
