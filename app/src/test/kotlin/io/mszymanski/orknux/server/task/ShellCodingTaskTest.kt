package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.shell.Shell
import io.mszymanski.orknux.connector.shell.ShellRepository
import io.mszymanski.orknux.connector.shell.ShellSessionRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.util.security.SecurityUtils
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.KeyPair
import java.time.Duration
import java.util.Base64

/**
 * A task told to write a program, and the program running.
 *
 * Everything else about tasks is pinned by [TaskLoopTest] against a stubbed
 * model, which is right for what that test asks: what the loop does with an
 * answer is decided by the loop, and a stub is the only way to put every answer
 * in front of it. This one asks the question a stub cannot be used for at all -
 * whether a model, given nothing but this product's three shell tools, can
 * actually build and deliver something. A stub that returns the commands a
 * working agent would have run proves that the commands work, which nobody
 * doubted.
 *
 * So the whole of it is real. A real model over the network, a real SSH server
 * with a JDK on it, a real git server in a container, a real Maven build of
 * whatever the model wrote, and an HTTP request to the thing that came out. The
 * only value this test supplies is the arithmetic the endpoint is asked to do,
 * and it computes that itself in [expected] so that the assertion is about the
 * application's answer rather than about a number written twice.
 *
 * **It needs a model, and it says so rather than passing.** There is no key in
 * this repository and none in CI - see the `build` job in `ci.yml`, which sets
 * no secrets - so without `ORKNUX_TEST_MODEL_KEY` this is skipped with a reason
 * that appears in the surefire report. Skipped rather than stubbed: a green run
 * of a test whose subject was replaced by a stand-in is worse than no test,
 * because it is a claim.
 *
 * **It is tagged `slow` and excluded from the ordinary run** (`app/pom.xml`),
 * because a model writing an application takes minutes and pulls three images.
 * `./mvnw test -Dorknux.test.excluded-groups=` runs it, with the key in the
 * environment.
 *
 * **This runs on the limits the product ships, and it did not always.** It was
 * written overriding two of them, and the overrides were the finding: a shell
 * command could run for a minute and keep 64 KiB of what it printed.
 * `docker/coder` is the box this product offers for exactly this work and it
 * ships with an empty Maven repository, so the first `mvn package` an agent
 * runs on it downloads Spring Boot before it compiles anything - minutes, not a
 * minute - and every machine's repository is empty once. An agent was handed a
 * timeout it did not cause and could not fix, and a failing build was cut before
 * the compile error, which left a model unable to tell a failure from a cut.
 * Both defaults moved for it, and one machine can now be given numbers of its
 * own; see `ShellProperties` and `Shell.commandTimeoutSeconds`. The overrides
 * are gone from here on purpose - a test that raises the limits it needs proves
 * the product works for whoever raises them, which is nobody.
 */
@Tag("slow")
@EnabledIfEnvironmentVariable(
    named = "ORKNUX_TEST_MODEL_KEY",
    matches = ".+",
    disabledReason = "No model to think with: set ORKNUX_TEST_MODEL_KEY (and optionally " +
        "ORKNUX_TEST_MODEL_ENDPOINT, ORKNUX_TEST_MODEL_ID, ORKNUX_TEST_MODEL_TYPE) to run this.",
)
@SpringBootTest(
    properties = [
        // Nothing sweeps a session out from under a build that is still running.
        "orknux.shell.sweep-initial-delay=1h",
        "orknux.shell.session-idle-timeout=4h",
    ],
)
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ShellCodingTaskTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val loop: TaskLoop,
    @Autowired val tasks: TaskRepository,
    @Autowired val requests: TaskRequestRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val shells: ShellRepository,
    @Autowired val shellSessions: ShellSessionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    /**
     * The suite's database is shared, and this test asks for "a shell".
     *
     * [io.mszymanski.orknux.connector.shell.ShellService.choose] picks among
     * every enabled shell in the installation, so a row another class left
     * behind is a machine this agent might land on instead - one with no JDK on
     * it, failing here for a reason that has nothing to do with this test. The
     * rest is cleared for the ordinary reason: the assertions read the audit
     * log and the sessions, and both are installation-wide.
     */
    @BeforeEach
    fun reset() {
        requests.deleteAll()
        tasks.deleteAll()
        events.deleteAll()
        shellSessions.deleteAll()
        shells.deleteAll()
        agents.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    /**
     * The whole of it, as one test.
     *
     * Not split into "the agent pushed something" and "the something runs",
     * because the second cannot be arranged without the first and a suite in
     * which one of a pair is meaningless on its own reads as two facts when it
     * holds one. What it costs is that a failure has to say where it happened,
     * which is what the assertion messages are for.
     */
    @Test
    fun `an agent writes a Spring application over a shell, pushes it, and what it pushed runs`() {
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "platform")).id)
        val modelId = model(workspaceId)
        shells.save(
            Shell(
                name = "jvm-coder",
                host = coder.host,
                port = coder.getMappedPort(SSH_PORT),
                username = ACCOUNT,
                privateKey = privateKey,
            ),
        )
        val agent = agents.save(
            Agent(
                workspaceId = workspaceId,
                name = "coder",
                type = AgentType.LLM,
                modelId = modelId,
                // Granted on the agent rather than asked for and approved. The
                // permission round trip is [TaskLoopTest]'s subject and would
                // cost this one two turns and a fixture pretending to be a
                // person; what is being proved here is on the other side of it.
                shellAccess = true,
            ),
        )

        val taskId = task(workspaceId, requireNotNull(agent.id), modelId)
        work(taskId)

        val task = requireNotNull(tasks.findByIdOrNull(taskId))
        assertThat(task.status)
            .describedAs("the task ended %s: %s", task.endedBecause, task.outcome ?: transcript(taskId))
            .isEqualTo(TaskStatus.DONE)

        // Through the shell and not some other way. Every command an agent runs
        // is audited, so the audit is where "it did the work on the machine"
        // is a fact rather than an inference from the result.
        val ran = audit.findAll().filter { it.category.name == "SHELL" }.map { it.message }
        assertThat(ran).describedAs("nothing was run on the machine").isNotEmpty()
        assertThat(ran).anySatisfy { assertThat(it).contains("mvn") }
        // "push" rather than "git push": `git -C somewhere push` is a command
        // somebody's model will eventually write, and an assertion that fails
        // on it would be an assertion about spelling.
        assertThat(ran).anySatisfy { assertThat(it).contains("push") }
        assertThat(shellSessions.findAll()).describedAs("no session was ever opened").isNotEmpty()

        // It is in git, on the branch that was asked for, with sources in it.
        assertThat(gitea("/api/v1/repos/$GITEA_ACCOUNT/$REPOSITORY/commits?limit=1"))
            .describedAs("the repository has no commits, so nothing was pushed")
            .contains("\"sha\"")
        assertThat(gitea("/api/v1/repos/$GITEA_ACCOUNT/$REPOSITORY/contents/pom.xml?ref=$BRANCH"))
            .describedAs("there is no pom.xml on %s", BRANCH)
            .contains("\"name\":\"pom.xml\"")

        // And what is in git builds and runs, fetched fresh onto a machine the
        // agent never touched. This is the assertion the issue asks for: not
        // that the model said it worked, but that a clone of what it pushed
        // answers the question the prompt set it.
        buildAndStart()
        VALUES.forEach { value ->
            assertThat(answerFor(value))
                .describedAs("GET %s?%s=%d", ENDPOINT_PATH, PARAMETER, value)
                .isEqualTo(expected(value).toString())
        }
    }

    /**
     * The arithmetic, written here once and asked of the application.
     *
     * Deliberately not a number a model could stumble into: three operations,
     * a modulus, and four values checked, so an endpoint that returns its
     * parameter, nought, or the first thing that came to mind fails on the
     * first of them.
     */
    private fun expected(value: Long): Long = (value * value + 3 * value + 7) % 1000

    /**
     * Turns, until it finishes or runs out of them.
     *
     * A task that parks is stopped here rather than answered. Parking means the
     * prompt did not say something the model needed, and the useful failure is
     * the question it asked - answering it from a fixture would hide a prompt
     * this test is also responsible for.
     */
    private fun work(taskId: Long) {
        repeat(TURNS) {
            when (val turn = loop.advance(taskId)) {
                is TaskTurn.Over -> return
                is TaskTurn.Parked -> {
                    val asked = requests.findAll().lastOrNull()?.asks
                    fail<Unit>("The task stopped to ask, after ${turn.after}: $asked")
                }

                is TaskTurn.Working -> log.info(
                    "Task {} took turn {} of {}",
                    taskId,
                    tasks.findByIdOrNull(taskId)?.turnsSpent,
                    TURNS,
                )
            }
        }
    }

    /**
     * The task row, made by hand.
     *
     * [TaskService.start] would set an engine going on a thread of its own, and
     * a test that then also drove the loop would have two callers taking turns
     * on one task. The same reason [TaskLoopTest] does it, and the row it builds
     * is the row the service would have built.
     */
    private fun task(workspaceId: Long, agentId: Long, modelId: Long): Long {
        val task = tasks.save(
            Task(
                workspaceId = workspaceId,
                title = "Write and publish the compute service",
                prompt = PROMPT,
                agentId = agentId,
                modelId = modelId,
                createdBy = "alice",
                turnsAllowed = TURNS,
                secondsAllowed = Duration.ofHours(1).toSeconds(),
            ),
        )
        val id = requireNotNull(task.id)
        task.sessionId = recorder.open(workspaceId, "task", id.toString())
        recorder.userSaid(requireNotNull(task.sessionId), "alice", PROMPT)
        tasks.save(task)
        return id
    }

    /** What the model was told and what it said, for a failure that has to be read. */
    private fun transcript(taskId: Long): String {
        val sessionId = tasks.findByIdOrNull(taskId)?.sessionId ?: return "there is no log"
        return events.findAll()
            .filter { it.sessionId == sessionId }
            .joinToString("\n") { "${it.kind}: ${(it.content.orEmpty() + it.result.orEmpty()).take(2000)}" }
    }

    /**
     * A provider and a model, through the doors a person would use.
     *
     * The GraphQL mutations rather than the repositories, because a provider
     * saved past its own validation is one whose endpoint was never checked and
     * whose key was never encrypted by the code that encrypts keys.
     */
    private fun model(workspaceId: Long): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Task model", type: $PROVIDER_TYPE,
                 endpoint: "$PROVIDER_ENDPOINT", secret: "$PROVIDER_KEY"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Task model", modelId: "$MODEL_ID", kind: CHAT,
                 contextWindow: 200000, maxOutput: 16000
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private companion object {

        private val log = LoggerFactory.getLogger(ShellCodingTaskTest::class.java)

        /** Ed25519 has one size; sshd wants it said anyway. */
        private const val ED25519_BITS = 256

        private const val SSH_PORT = 22
        private const val APPLICATION_PORT = 8080
        private const val GITEA_PORT = 3000

        /** The one account `docker/coder`'s sshd will let in; see its sshd_config. */
        private const val ACCOUNT = "coder"

        /** Where that image puts work, and the only directory the account owns. */
        private const val WORK = "/work"

        /** Reachable under this name from the other containers on the network. */
        private const val GITEA_HOST = "gitea"
        private const val GITEA_ACCOUNT = "orknux"
        private const val GITEA_PASSWORD = "orknux-test-password"
        private const val REPOSITORY = "compute-service"
        private const val BRANCH = "main"

        private const val ENDPOINT_PATH = "/compute"
        private const val PARAMETER = "value"

        /**
         * How many turns the agent gets.
         *
         * Fewer than the installation's forty on purpose. A model that has not
         * written, built and pushed one endpoint in twenty rounds is not one
         * round away from it, and the difference between the two numbers is
         * twenty rounds of a real model on a failing run.
         */
        private const val TURNS = 20

        /** Four, so an endpoint that answers something constant fails on the second. */
        private val VALUES = listOf(0L, 4L, 17L, 913L)

        private val PROVIDER_KEY: String = System.getenv("ORKNUX_TEST_MODEL_KEY").orEmpty()
        private val PROVIDER_ENDPOINT: String = setting("ORKNUX_TEST_MODEL_ENDPOINT", "https://api.anthropic.com/v1")
        private val PROVIDER_TYPE: String = setting("ORKNUX_TEST_MODEL_TYPE", "ANTHROPIC")
        private val MODEL_ID: String = setting("ORKNUX_TEST_MODEL_ID", "claude-sonnet-4-5")

        private fun setting(name: String, fallback: String): String =
            System.getenv(name)?.trim()?.ifEmpty { null } ?: fallback

        /**
         * What the agent is asked to do.
         *
         * Written the way an operator would write it, and long for a reason:
         * every sentence in it is either the specification of the thing being
         * built or a fact about the machine that a model would otherwise spend
         * a turn discovering. The turn budget is the scarce resource here, and
         * a prompt that saves two rounds of `which mvn` is a prompt that leaves
         * two rounds for the work.
         *
         * The three facts about git are there because `docker/coder` sets none
         * of them, deliberately - it is a box for building anybody's project,
         * not a box that has opinions about whose commits they are. So the
         * first `git commit` on it refuses for want of a name, and `git init`
         * makes `master`. Both are one command to fix and a whole turn to
         * discover.
         */
        private val PROMPT = """
            You have a machine you can run commands on. Do the work on it - open a session, and use it.
            Do not describe what you would do.

            Write, from nothing, a Spring Boot web application in Java, and publish it to this
            installation's git server.

            The application:
            - A Maven project. Parent org.springframework.boot:spring-boot-starter-parent version 3.5.6,
              the property java.version set to 21, the single dependency spring-boot-starter-web, and
              spring-boot-maven-plugin in the build so that packaging produces a runnable jar. Keep to
              those versions; they are known to build on this machine.
            - It listens on port 8080 and answers GET $ENDPOINT_PATH, which takes one query parameter
              called $PARAMETER holding a whole number that is never negative.
            - The answer is the plain text of (value * value + 3 * value + 7) modulo 1000, and nothing
              else: no JSON, no quotes, no words around it. Do the arithmetic in long.
            - `mvn -q -DskipTests package` has to succeed, and target/ has to hold a jar that
              `java -jar` starts.

            Then publish it:
            - Make it a git repository in the directory you built it in, commit every source file, and
              push the branch $BRANCH to
              http://$GITEA_ACCOUNT:$GITEA_PASSWORD@$GITEA_HOST:$GITEA_PORT/$GITEA_ACCOUNT/$REPOSITORY.git
            - That repository already exists and is empty. Do not commit target/.
            - Read what the push said and be sure it succeeded before you say you have finished.
              Somebody else is going to clone that repository and build it, so what is pushed has to be
              everything it needs.

            About the machine:
            - java, mvn and git are all on the PATH, and it has a network.
            - git on it has no name and no email, and `git init` makes a branch called master. Set what
              you need before you commit, and make sure what you push is called $BRANCH.
            - Its Maven repository is empty, so the first build downloads what it needs before it
              compiles anything. That is normal here and it is not a failure.
            - Build with `mvn -q -DskipTests package`. Long output is cut off, so ask for no more than
              you need, and when something fails look at the part of the output that says why.
            - A single command may take up to ten minutes, which is enough for a build.
            - Work in the session's own directory. It is empty and it is yours.

            When the push has succeeded, call task_done and say where the repository is.
        """.trimIndent()

        private val http: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        /**
         * One network, because two of these three containers have to find each
         * other by name.
         *
         * The agent pushes to `gitea:3000` from inside the machine it is
         * working on, and the mapped port the test uses would mean nothing
         * there. Testcontainers' own network gives both a name they can resolve.
         */
        private val network: Network = Network.newNetwork()

        private lateinit var privateKey: String

        /**
         * The box this product offers, built from `docker/coder`.
         *
         * The image the issue asked for, rather than one invented for a test.
         * That is the whole point of running it here: the thing being proved is
         * that an administrator who stands `orknux-jvm-coder` up beside an
         * installation and points a Shell at it has given an agent somewhere it
         * can actually do this work. A second image built to make the test pass
         * would prove that the test's own image works.
         */
        private const val IMAGE = "orknux-jvm-coder:test"

        /**
         * Built by the docker command line, and it has to be.
         *
         * Testcontainers' `ImageFromDockerfile` goes through docker-java, which
         * speaks to the daemon's classic builder and has no BuildKit. That
         * builder does not understand `COPY --chmod` or a heredoc in a `RUN`,
         * and `docker/coder/Dockerfile` uses both - it refuses the build with a
         * message about BuildKit rather than doing anything odd, which is at
         * least a clear failure. Shelling out is what `docker compose -f
         * docker/coder/compose.yaml up --build` would do anyway, so this builds
         * the image the way the only documented way of building it does.
         *
         * Under a fixed tag, so a second run on a machine reuses Docker's
         * layers - which matters here, because those layers are several JDKs,
         * Maven, Gradle and Node.
         */
        private fun buildImage() {
            // Surefire runs with the module directory current, and the image
            // belongs to the repository rather than to this module.
            val context = Path.of("..", "docker", "coder").toAbsolutePath().normalize()
            log.info("Building {} from {}", IMAGE, context)

            val building = ProcessBuilder("docker", "build", "-t", IMAGE, context.toString())
                .redirectErrorStream(true)
                .start()
            val said = building.inputStream.bufferedReader().use { it.readText() }
            check(building.waitFor() == 0) { "$IMAGE would not build:\n${said.takeLast(8000)}" }
        }

        /** The machine the agent is given. */
        private val coder: GenericContainer<*> by lazy {
            /*
             * Generated through sshd's own Ed25519 support rather than the
             * JDK's, for the reason ShellSessionTest gives: the two
             * representations are not interchangeable, and a key written the
             * way sshd reads it is the key an administrator's would be.
             */
            val pair = SecurityUtils.getOpenSSHEDDSAPrivateKeyEntryDecoder().generateKeyPair(ED25519_BITS)
            privateKey = openSshPrivateKey(pair)
            machine(pair)
        }

        /**
         * A second machine, which the agent never sees.
         *
         * Everything after the push happens here: clone, build, run. On a
         * machine of its own rather than the one the work was done on, because
         * a build that succeeds in the directory it was written in proves
         * nothing about what was committed - a file the agent forgot to `git
         * add` is exactly the failure this catches, and it is invisible from
         * the other side.
         */
        private val verifier: GenericContainer<*> by lazy {
            val pair = SecurityUtils.getOpenSSHEDDSAPrivateKeyEntryDecoder().generateKeyPair(ED25519_BITS)
            machine(pair).withExposedPorts(SSH_PORT, APPLICATION_PORT)
        }

        private val gitea: GenericContainer<*> by lazy {
            GenericContainer("gitea/gitea:1.24")
                .withNetwork(network)
                .withNetworkAliases(GITEA_HOST)
                .withExposedPorts(GITEA_PORT)
                // SQLite and the installer already locked, so it comes up
                // usable rather than on its own first-run form.
                .withEnv("GITEA__database__DB_TYPE", "sqlite3")
                .withEnv("GITEA__security__INSTALL_LOCK", "true")
                .withEnv("GITEA__service__DISABLE_REGISTRATION", "true")
                // What it will hand out as a clone URL, which has to be the
                // name the other containers know it by.
                .withEnv("GITEA__server__ROOT_URL", "http://$GITEA_HOST:$GITEA_PORT/")
                // Nothing in this test may reach out for an avatar or a font.
                .withEnv("GITEA__server__OFFLINE_MODE", "true")
                .waitingFor(
                    Wait.forLogMessage(".*Starting new Web server.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(3)),
                )
        }

        /**
         * One of those boxes, told whose key gets in.
         *
         * `ORKNUX_AUTHORIZED_KEYS` is the image's own way of being given one -
         * its entrypoint refuses to start without either that or a mounted
         * file, which is why nothing here has to arrange a password.
         *
         * Waited for by what sshd says once it is bound. Not by the port, which
         * is what Testcontainers would do by default: the verifier also
         * publishes 8080, nothing listens there until the application it has
         * not fetched yet is running, and a default wait would sit there until
         * it gave up.
         */
        private fun machine(pair: KeyPair): GenericContainer<*> =
            GenericContainer(IMAGE)
                .withNetwork(network)
                .withEnv("ORKNUX_AUTHORIZED_KEYS", PublicKeyEntry.toString(pair.public))
                .withExposedPorts(SSH_PORT)
                .waitingFor(
                    Wait.forLogMessage(".*Server listening on 0\\.0\\.0\\.0 port $SSH_PORT.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(5)),
                )

        @JvmStatic
        @BeforeAll
        fun start() {
            buildImage()
            gitea.start()
            coder.start()
            verifier.start()
            makeAccount()
            makeRepository()
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            listOf(verifier, coder, gitea).forEach { runCatching { it.stop() } }
            runCatching { network.close() }
        }

        /** The account the agent pushes as, made the way gitea's own documentation makes one. */
        private fun makeAccount() {
            val made = gitea.execInContainer(
                "su",
                "git",
                "-c",
                "gitea admin user create --username $GITEA_ACCOUNT --password $GITEA_PASSWORD " +
                    "--email coder@orknux.invalid --admin --must-change-password=false",
            )
            check(made.exitCode == 0) { "gitea would not make an account: ${made.stderr}${made.stdout}" }
        }

        /**
         * An empty repository, made before the task starts.
         *
         * Made here rather than left to the agent because creating one is a
         * call to gitea's API with an administrator's credentials, and handing
         * those to the agent would be testing gitea rather than this product.
         * What is being proved is that the agent can push, and a push needs
         * somewhere to push to.
         */
        private fun makeRepository() {
            val made = giteaRequest("/api/v1/user/repos")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"name":"$REPOSITORY","auto_init":false,"private":false,"default_branch":"$BRANCH"}""",
                    ),
                )
                .build()
            val answer = http.send(made, HttpResponse.BodyHandlers.ofString())
            check(answer.statusCode() in 200..299) { "gitea would not make a repository: ${answer.body()}" }
        }

        private fun gitea(path: String): String {
            val answer = http.send(giteaRequest(path).GET().build(), HttpResponse.BodyHandlers.ofString())
            check(answer.statusCode() in 200..299) { "gitea answered ${answer.statusCode()} to $path: ${answer.body()}" }
            return answer.body()
        }

        private fun giteaRequest(path: String): HttpRequest.Builder {
            val credentials = Base64.getEncoder()
                .encodeToString("$GITEA_ACCOUNT:$GITEA_PASSWORD".toByteArray())
            return HttpRequest.newBuilder(
                URI("http://${gitea.host}:${gitea.getMappedPort(GITEA_PORT)}$path"),
            ).header("Authorization", "Basic $credentials")
        }

        /**
         * Clone what was pushed onto the other machine, build it, and start it.
         *
         * Over `docker exec` rather than over SSH, because this half is the
         * test doing its own work and not an agent doing its. Using the shell
         * bridge here would mean a failure of the bridge could be read as a
         * failure of what the agent wrote.
         */
        private fun buildAndStart() {
            run(
                "git clone --branch $BRANCH " +
                    "http://$GITEA_ACCOUNT:$GITEA_PASSWORD@$GITEA_HOST:$GITEA_PORT/" +
                    "$GITEA_ACCOUNT/$REPOSITORY.git $WORK/fetched",
            )
            run("cd $WORK/fetched && mvn -B -q -DskipTests package")
            /*
             * The repackaged jar, and not the plain one beside it.
             * spring-boot-maven-plugin leaves the original as `.jar.original`
             * on some layouts and as a second artifact on others, and starting
             * the wrong one fails with a missing main class that reads like the
             * application is broken.
             */
            run(
                "cd $WORK/fetched && JAR=\$(ls target/*.jar | grep -v '\\.original' | head -n 1) && " +
                    "nohup java -jar \"\$JAR\" --server.port=$APPLICATION_PORT --server.address=0.0.0.0 " +
                    "> /tmp/application.log 2>&1 & echo started",
            )
        }

        /**
         * One command on the verifier, as the account that owns the toolchain.
         *
         * `su - coder`, and the dash is load-bearing. The image puts Maven and
         * the JDKs under that account's home through SDKMAN and puts them on
         * the path from a file its `.profile` sources, so a command run as root
         * - which is what `docker exec` is by default - finds no `mvn` at all.
         * The login shell is what reads that file.
         */
        private fun run(command: String) {
            log.info("On the verifier: {}", command)
            val outcome = verifier.execInContainer("su", "-", ACCOUNT, "-c", command)
            check(outcome.exitCode == 0) {
                "`$command` exited ${outcome.exitCode} on the verifier:\n${outcome.stdout}\n${outcome.stderr}"
            }
        }

        /**
         * What the application answers, once it is answering.
         *
         * The first call waits for a Spring Boot application to start, which is
         * seconds and occasionally more; the ones after it answer at once. One
         * poll rather than a separate readiness wait, so a start that never
         * happens fails with the application's own log rather than with a
         * timeout that says nothing.
         */
        private fun answerFor(value: Long): String {
            val request = HttpRequest.newBuilder(
                URI(
                    "http://${verifier.host}:${verifier.getMappedPort(APPLICATION_PORT)}" +
                        "$ENDPOINT_PATH?$PARAMETER=$value",
                ),
            ).timeout(Duration.ofSeconds(10)).GET().build()

            val giveUpAt = System.nanoTime() + Duration.ofMinutes(3).toNanos()
            var last: String? = null
            while (System.nanoTime() < giveUpAt) {
                val answer = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
                    .onFailure { last = it.message }
                    .getOrNull()
                if (answer != null && answer.statusCode() == 200) return answer.body().trim()
                if (answer != null) last = "HTTP ${answer.statusCode()}: ${answer.body().take(500)}"
                Thread.sleep(2000)
            }

            val logged = runCatching { verifier.execInContainer("cat", "/tmp/application.log").stdout }
                .getOrDefault("(no log)")
            throw AssertionError(
                "The application never answered for $PARAMETER=$value ($last). Its log said:\n" +
                    logged.takeLast(4000),
            )
        }

        /** The key as somebody would paste it, which is the form being tested. */
        private fun openSshPrivateKey(pair: KeyPair): String {
            val written = ByteArrayOutputStream()
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "orknux-test", null, written)
            return written.toString(Charsets.UTF_8)
        }
    }
}
