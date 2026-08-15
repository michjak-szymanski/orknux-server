package io.mszymanski.gyloli.server.trigger

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.sql.DataSource

/**
 * Fires the scheduled triggers.
 *
 * db-scheduler owns the clock: the tick below is a recurring task in the
 * database, so it runs once a minute however many instances are up, and a
 * restart picks it up where it was rather than firing everything again.
 *
 * What the tick does is compare each enabled definition's cron with when it last
 * fired. That is what makes a trigger's schedule editable while the process
 * runs — nothing is registered per trigger, so nothing has to be unregistered —
 * and it is why the smallest schedule this supports is a minute.
 */
@Component
class TriggerScheduler(
    private val triggers: WorkflowTriggerRepository,
    private val runs: TriggerRunner,
) {

    /**
     * Fires everything that came due since it last ran.
     *
     * A trigger that has never fired starts from now, so adding one does not
     * replay the schedule it missed.
     */
    @Transactional
    fun tick(now: OffsetDateTime = OffsetDateTime.now()) {
        for (trigger in triggers.findByTypeAndEnabledTrue(TriggerType.SCHEDULED)) {
            val cron = trigger.cron ?: continue
            val zone = zoneOf(trigger.timezone)
            val expression = runCatching { CronExpression.parse(sixField(cron)) }.getOrElse {
                log.warn("Trigger {} has a cron this cannot schedule: {}", trigger.name, cron)
                continue
            }

            val since = trigger.lastFiredAt ?: now.minusMinutes(1)
            val due = expression.next(since.atZoneSameInstant(zone).toOffsetDateTime())
            if (due == null || due.isAfter(now)) continue

            trigger.lastFiredAt = now
            runs.fire(trigger, mapOf("cron" to cron, "firedAt" to now.toString()))
        }
    }

    private fun zoneOf(timezone: String?): ZoneId =
        runCatching { ZoneId.of(timezone ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))

    private companion object {
        val log = LoggerFactory.getLogger(TriggerScheduler::class.java)
    }
}

/**
 * Builds the scheduler and starts it.
 *
 * This is done here rather than by db-scheduler's Spring Boot starter: that
 * starter's auto-configuration is `@ConditionalOnBean(DataSource)` ordered after
 * Boot 3's `DataSourceAutoConfiguration`, and Boot 4 moved that class to another
 * package — so the ordering no longer applies, the condition is evaluated before
 * the datasource is defined, and nothing is ever scheduled. Six lines here are
 * cheaper than a silence nobody notices.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TriggerSchedulerProperties::class)
@ConditionalOnProperty(prefix = "db-scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class TriggerSchedulerConfig {

    /** One task, one instance, every minute: whoever picks it up does the round. */
    @Bean
    fun scheduledTriggerTask(scheduler: TriggerScheduler): RecurringTask<Void> = Tasks
        .recurring("scheduled-triggers", Schedules.cron("0 * * * * *"))
        .execute { _, _ ->
            runCatching { scheduler.tick() }
                .onFailure { log.error("The scheduled triggers could not be fired", it) }
        }

    @Bean(destroyMethod = "stop")
    fun dbScheduler(
        dataSource: DataSource,
        tasks: List<RecurringTask<*>>,
        properties: TriggerSchedulerProperties,
    ): Scheduler = Scheduler.create(dataSource)
        .startTasks(tasks)
        .threads(properties.threads)
        .pollingInterval(properties.pollingInterval)
        .registerShutdownHook()
        .build()
        .also {
            it.start()
            log.info("Scheduler started with {} recurring task(s)", tasks.size)
        }

    private companion object {
        val log = LoggerFactory.getLogger(TriggerSchedulerConfig::class.java)
    }
}

@ConfigurationProperties(prefix = "db-scheduler")
data class TriggerSchedulerProperties(
    /** False starts no clock at all; the tests run that way. */
    val enabled: Boolean = true,
    val threads: Int = 4,
    /** How often the table is asked what is due. */
    val pollingInterval: Duration = Duration.ofSeconds(10),
)
