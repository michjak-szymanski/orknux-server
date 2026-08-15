package io.mszymanski.gyloli.workflow.temporal

import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.RetryOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import io.temporal.worker.WorkflowImplementationOptions
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The Temporal client and the worker that runs in this process.
 *
 * The stubs are not connected on creation, so this service starts whether or
 * not Temporal is up — the same rule the GraphQL upstreams follow. A run
 * started while it is down fails at the start, rather than the application
 * refusing to boot.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TemporalProperties::class)
@ConditionalOnProperty(name = ["gyloli.temporal.enabled"], havingValue = "true", matchIfMissing = true)
class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    fun workflowServiceStubs(properties: TemporalProperties): WorkflowServiceStubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(properties.target)
                .build(),
        )

    @Bean
    fun workflowClient(stubs: WorkflowServiceStubs, properties: TemporalProperties): WorkflowClient =
        WorkflowClient.newInstance(
            stubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(properties.namespace)
                .build(),
        )

    /**
     * One worker, polling one queue, running both the interpreter and the
     * activities. They are split when a step becomes something worth scaling on
     * its own — a model call and a graph walk want different machines.
     */
    @Bean(destroyMethod = "shutdown")
    fun workerFactory(
        client: WorkflowClient,
        activities: ExecutionActivities,
        properties: TemporalProperties,
    ): WorkerFactory {
        val factory = WorkerFactory.newInstance(client)
        val worker = factory.newWorker(properties.taskQueue)

        worker.registerWorkflowImplementationTypes(
            WorkflowImplementationOptions.newBuilder()
                .setDefaultActivityOptions(activityOptions(properties))
                .build(),
            ExecutionWorkflowImpl::class.java,
        )
        worker.registerActivitiesImplementations(activities)
        return factory
    }

    private fun activityOptions(properties: TemporalProperties): ActivityOptions =
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(properties.stepTimeoutSeconds))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(properties.stepAttempts)
                    .build(),
            )
            .build()
}

/**
 * Starts polling once the application is up, and stops before it goes down, so
 * a worker never picks up a step this process is no longer able to finish.
 */
@Component
@ConditionalOnProperty(name = ["gyloli.temporal.enabled"], havingValue = "true", matchIfMissing = true)
class TemporalWorkerLifecycle(
    private val factory: WorkerFactory,
    private val properties: TemporalProperties,
) : SmartLifecycle {

    private var running = false

    override fun start() {
        factory.start()
        running = true
        log.info("Polling Temporal at {} on {}", properties.target, properties.taskQueue)
    }

    override fun stop() {
        if (!running) return
        // Lets the steps in flight finish rather than dropping them on the floor.
        factory.shutdown()
        factory.awaitTermination(SHUTDOWN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        running = false
    }

    override fun isRunning(): Boolean = running

    private companion object {
        val log = LoggerFactory.getLogger(TemporalWorkerLifecycle::class.java)
        const val SHUTDOWN_SECONDS = 10L
    }
}
