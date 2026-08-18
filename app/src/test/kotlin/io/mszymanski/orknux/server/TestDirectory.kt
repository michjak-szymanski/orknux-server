package io.mszymanski.orknux.server

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The directory the suite signs in against: its own, thrown away with the run.
 *
 * Sign-in is checked against a real LDAP server rather than a stub, which is the
 * point of those tests — the group-to-authority mapping is the thing most likely
 * to be wrong, and a stub that returns what the code asks for cannot tell you
 * that. It used to be the container from `compose.yaml`, which works on a
 * developer's machine and nowhere else: on a runner there is no compose, so
 * every one of those tests failed for want of something to talk to, and the
 * failure said "connection refused" rather than "start the directory".
 *
 * A launcher listener, like [TestDatabase], for the same reason: it has to be up
 * before the first Spring context reads `spring.ldap.urls`.
 *
 * The fixtures are `docker/ldap/bootstrap.ldif`, the same file compose mounts —
 * copied in rather than duplicated into test resources, because two accounts of
 * who `alice` is would eventually disagree, and the one the tests trust would be
 * the one nobody deploys.
 */
class TestDirectory : LauncherSessionListener {

    override fun launcherSessionOpened(session: LauncherSession) {
        if (started) return
        started = true

        val fixtures = bootstrapLdif()
        if (fixtures == null) {
            // Nothing to seed it with means nothing to sign in as, so leave the
            // configured directory alone rather than start an empty one and fail
            // in a way that points at the wrong thing.
            System.err.println("TestDirectory: docker/ldap/bootstrap.ldif not found; using the configured directory")
            return
        }

        val directory = GenericContainer("osixia/openldap:1.5.0")
            // --copy-service copies the mounted fixtures in before slapd starts;
            // without it they are applied to a server that is already running,
            // which is a race the suite would lose about one run in ten.
            .withCommand("--copy-service")
            .withEnv("LDAP_ORGANISATION", "Orknux")
            .withEnv("LDAP_DOMAIN", "orknux.io")
            .withEnv("LDAP_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(
                MountableFile.forHostPath(fixtures),
                "/container/service/slapd/assets/config/bootstrap/ldif/custom/50-bootstrap.ldif",
            )
            .withExposedPorts(LDAP_PORT)
            // The port opens before the fixtures are in, so the wait is for what
            // the seeding says when it has finished.
            .waitingFor(Wait.forLogMessage(".*slapd starting.*", 1).withStartupTimeout(Duration.ofMinutes(2)))

        directory.start()

        // Outranks application.yml, so every context built afterwards finds this
        // one rather than whatever is on the machine's own port 389.
        System.setProperty(
            "spring.ldap.urls",
            "ldap://${directory.host}:${directory.getMappedPort(LDAP_PORT)}",
        )
    }

    /** The fixtures, found from wherever the module happens to be run from. */
    private fun bootstrapLdif(): Path? =
        listOf(
            Path.of("docker", "ldap", "bootstrap.ldif"),
            Path.of("..", "docker", "ldap", "bootstrap.ldif"),
        ).firstOrNull { Files.exists(it) }?.toAbsolutePath()?.normalize()

    private companion object {
        const val LDAP_PORT = 389
        private var started = false
    }
}
