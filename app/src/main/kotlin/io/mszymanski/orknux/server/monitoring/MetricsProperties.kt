package io.mszymanski.orknux.server.monitoring

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "orknux.metrics")
data class MetricsProperties(
    /**
     * Whether `/actuator/prometheus` answers a caller who has not signed in.
     *
     * False, and it is the only thing here because it is the only decision worth
     * making. A scrape is not a page of numbers about a JVM: it says how many
     * workspaces this installation has, how often its workflows run, how often
     * they fail and which models are configured. On a public address that is an
     * account of somebody's estate, kept up to date, that nobody asked to publish.
     *
     * Turning it on is for the deployment where the scrape crosses a network only
     * the scraper is on and a credential in its config would be ceremony over a
     * wire nobody else can reach. Everywhere else the default is cheap to work
     * with: Prometheus sends an Authorization header where it is given one, and an
     * API token is read on the way in like any other caller's.
     *
     * It opens the metrics and nothing else. Every other Actuator endpoint is
     * unexposed rather than merely protected — see the `management` block in
     * application.yml — so this cannot widen into them.
     *
     * And it is where a fresh installation starts rather than the last word. The
     * same switch is on the Admin screen, and once it has been pressed the stored
     * answer is the one that holds — see `InstallationSettings.metricsAnonymous`
     * for why this one is not given the floor the others get.
     */
    val anonymous: Boolean = false,
)
