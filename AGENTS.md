# Working in orknux-server

Notes for anyone — human or agent — changing this repository. See
[README.md](README.md) for what the service is and how to run it.

## The name

**Orknux**. It is a coined word rather than a derivation, so there is no
etymology to tell — it was chosen for how it sounds and because it was
unclaimed. Write it in full in prose, and capitalised: it is the product's name,
not a command.

**`orkx` is the short form, and it belongs to identifiers rather than to prose.**
It is what appears where the full name would be long or where it has to be typed:
the command line client, the access token prefix `orkx_`, and the envelope an
encrypted column is kept in, `orkx1:`. A short form written into a stored value
outlives the release that chose it, which is why there is exactly one and why
nothing else should invent a second.

The identifiers have not followed the name. The Kotlin package is still
`io.mszymanski.orknux`, the Maven artifacts are still `orknux-*`, the config
properties are still `orknux.*`, and the database, its user and the LDAP base DN
are still `orknux`. Those are not cosmetic: the database name is where the
development data actually lives and the LDAP DN is what the seeded users are
under, so renaming them is a migration rather than a find-and-replace. Do not
rename them halfway.

## Commands

```
docker compose up -d                     # postgres, openldap and temporal
./mvnw spring-boot:run -pl app -am       # http://localhost:8080
./mvnw test                              # every module
./mvnw test -Dtest=IntegrationAPITest    # one class
```

`ORKNUX_SECRET_KEY` has to be set in the environment the server is started in.
It is read on first use, so without it the application starts perfectly, reports
itself healthy, and fails the first time anything reads or writes a credential.

Three Maven modules: `app` (the deployable), `modules/connection` and
`modules/execution`. Neither module may depend on `app`; where one needs
something the app owns it declares an interface — `WorkspaceDirectory`,
`WorkflowGraphSource` — and the app implements it.

`spring-boot:run` forks a JVM; stopping the Maven process can leave it holding
port 8080. Kill the process whose command line contains `OrknuxServerKt`.

**`-am` is not optional.** `-pl app` on its own resolves the modules from the
local repository rather than from the reactor, so after a change under `modules/`
it compiles against a stale jar and fails with unresolved references to code that
is plainly there in the source. `-am` builds them alongside; `./mvnw install
-DskipTests` first is the other way out. A plain `./mvnw test` builds the whole
reactor and does not have the problem.

## Stack quirks worth knowing

- **Spring Boot 4 ships Jackson 3.** The Jackson 2 Kotlin module does not apply,
  so DTOs bound from JSON need explicit `@JsonCreator` / `@JsonProperty`
  (`SessionAPI` shows the pattern). GraphQL inputs are unaffected.
- **`TestRestTemplate` is gone.** HTTP tests use `RestClient` with
  `defaultStatusHandler({ true }, { _, _ -> })` so error responses can be
  asserted on, plus `@LocalServerPort`.
- **Optional filters use JPA `Specification`s**, not JPQL. `:enum IS NULL OR …`
  fails in Hibernate 6; `WorkspaceAuditRepository.auditFilter` is the shape to copy.
- **Java 25, Kotlin 2.4.** The Maven wrapper is checked in; use `./mvnw`.
- **A Boot 3 starter may do nothing on Boot 4.** db-scheduler's
  auto-configuration is `@ConditionalOnBean(DataSource)` ordered after Boot 3's
  `DataSourceAutoConfiguration`, which moved package — so it silently never
  applied and no schedule ever fired. `TriggerSchedulerConfig` builds the
  scheduler itself, and `TriggerSchedulerIntegrationTest` starts it for real.
  Any third-party starter here deserves a test that it did something.
- `@EntityScan` moved in Boot 4: it is
  `org.springframework.boot.persistence.autoconfigure.EntityScan`.

## Schema

Flyway owns the schema and `ddl-auto` is `validate`, so every change is a new
`app/src/main/resources/db/migration/V<n>__<name>.sql` — one history for every
module, because there is one database. Never edit a migration that has run.
Entity and migration are changed together, or the application will not start.

Module tables carry no foreign keys across a module boundary, so a deleted workspace
is reported to the module rather than cascaded.

## Conventions

- One `@Controller` per aggregate, with `@QueryMapping` / `@MutationMapping`
  methods and the DTOs, page wrapper and exceptions in the same file. An
  `…ExceptionResolver` maps those exceptions to GraphQL error types.
- **Every resolver checks access first.** `access.requireAdmin()` for
  admin-level work, `access.requireVisible(workspace)` for anything workspace-scoped.
  A resolver that loads by id resolves the owning workspace and checks that.
- **Every change writes an audit entry**, worded as the UI shows it
  ("MCP Server brave-search added"), with the right category.
- **The modules hold no notion of a user.** A controller in `app` checks access,
  calls the module service, and records the audit entry — in that order. A module
  class that wants to know who is asking has a design problem.
- **GraphQL lives in `app` only.** The modules expose services; the schema, the
  controllers and the error mapping are the app's.
- **Wiring is the app's job**: `OrknuxServer` scans, entity-scans and
  repository-scans `io.mszymanski.orknux`, because a module cannot register
  itself into a context it does not own.
- **Credentials are read in one place.** `ConnectionTarget` resolves them for
  outbound calls; nothing else should touch a `secret` field. A Slack connection
  also holds an `appToken`, which is what Socket Mode opens the websocket with,
  and an SMTP connection's password is that same `secret` column — a second
  password column would be a second one to remember to encrypt. Every one of them
  goes through `SecretConverter`, so a new credential is a `@Convert` on the
  entity and nothing else.
- **What arrives is published, not called.** `modules/connection` opens the
  sockets and knows nothing of triggers, so `SlackListener` publishes an
  `IncomingEvent` and `IncomingTriggerListener` in `app` decides what runs. The
  two `…Action` enums are matched by name, and a test holds them together.
- **Chat history is Spring AI's, not ours.** Messages live in
  `SPRING_AI_CHAT_MEMORY` keyed by a conversation id; `chat_session` holds only
  what that store does not. Do not add a messages table — a workflow run is
  meant to key a conversation the same way so its agents share one thread.
  Flyway creates that table and `initialize-schema` is `never`.
- **A model is only ever offered tools that will run.** `AgentTools` is the one
  place that knows every built-in, and what it offers depends on the agent's
  grants. A tool declared but not implemented is a model told it can do something
  it cannot, and it will believe you.
- **What a listener cannot deliver is not offered.** `DeliverableActions` names
  the events something actually publishes — Slack mentions, and nothing else yet.
  The enum holds the vocabulary the product intends; the picker offers what is
  wired, and saving a trigger on anything else is refused. A definition that is
  enabled, instanced and silent for ever is worse than one that could not be
  saved.
- **A tool is not a function.** Both are the workspace's JavaScript in the same
  sandbox, and both have their own table. What differs is the caller: an action
  node calls a function at a point the graph fixed, an agent calls a tool if it
  judges that it should. Do not fold them into one table with a flag.
- **A workspace's JavaScript is hostile input.** It runs through `ScriptRunner` and
  nowhere else: host access, class loading, IO, threads, processes and
  environment are all denied, limits are set, and everything crossing the
  boundary is JSON text. Widening any of that needs a reason and a test.
- **What an action needs is derived, not stored.** `ActionAPI.inputsOf` reads
  the placeholders off the settings; a second copy in the database would drift.
- **A graph is checked by ports, not by kinds.** `GraphValidator` asks each node
  what it needs and gives, and compares that along the edges; a new node kind is
  a new branch in `portsOf` and nothing else. Only shapes that could never run
  are refused on save — the rest is advice, because a workflow is drawn before it
  is finished.
- **Catalogues, then instances.** Triggers, actions and conditions are defined
  once per workspace and used by pointing a node at one. What the thing *does*
  belongs to the catalogue entry, and a run keeps its own copy of which entry it
  used. A node stores the id, its own label, and what it passes — see below.
- **A node decides what it passes.** `workflow_node_mapping` holds one row per
  parameter, and that is what runs: `ActionNodeRunner` reads the mappings off the
  step, never off the action. The action's own `mappings` are a *seed* — read once
  in `WorkflowGraphAPI.mappingsFor` when a node is saved, and never again. This is
  why two nodes can call one function with different arguments, and why a plain
  value ("verbose") works where the definition only ever held an expression.
  Editing a node must not write to a trigger, action or condition.
- **A trigger is a catalogue entry, not a binding.** `workflow_trigger` names no
  workflow; a `workflow_node` of kind `TRIGGER` carries `trigger_id`, and that
  instance is the wiring. An arriving event matches definitions first and finds
  their instances second — `IncomingTriggerListener` shows both halves.
- **Saving is the draft; publishing is a copy.** The `workflow_node` and
  `workflow_edge` rows are the draft an editor writes to. `publishWorkflow` writes
  a snapshot of the runnable graph into `workflow_publication`, and that is what a
  trigger, a schedule or the API runs — `ExecutionPlanner` asks for
  `GraphVersion.DRAFT` only when the run was `MANUAL`. Anything that changes what
  a graph does has to be part of the snapshot, or it will be edited and not run;
  `WorkflowSnapshot` reads and writes it by hand rather than by reflection,
  because a shape in a database outlives the class it came from.
- **Access is decided in roles, not in the provider's vocabulary.** A directory
  group or an OIDC claim is translated once, by `RoleResolver`, and everything
  past the front door — who administers, who sees which workspace — deals only in
  `Role`. Do not add a check that reads an authority string directly.
- **A notification is written by the desk, never by the screen that caused it.**
  `IssueNewsDesk` is where an event becomes news for somebody, so the bell and
  `orknux_news` cannot disagree about what happened. Reading marks read, so a
  reader that must not clear the count asks for `waiting`, not `unread`.
- **An issue is addressed by its number.** `#4` is per workspace and is what a
  URL, a tool call and a person all say; the row id is an implementation detail
  that should not reach an API or a link.
- **There is one attachment store.** Chat attachments and issue attachments share
  `AttachmentStore`, `InstallationSettings` and the list of what may be served
  inline. A second copy of the inline rule is how one of them ends up serving
  something the other refuses.
- **A tool the MCP endpoint offers is a tool an agent can be given.**
  `OrknuxTools` is the one place that knows the `orknux_*` surface, and the scope
  it is called with is what decides whether writing is offered. Adding a tool in
  one place and not the other is two products.
- Comments say why, not what. KDoc on public types and anything with a rule
  behind it.

## Tests

Every API change comes with `GraphQlTester` coverage in the matching package,
driving the real modules — there is no stand-in for a module now that they are in
the same process. Watch out for:

- `entityList(String)` fails on object arrays — select a scalar path, or use
  `.get()` and assert with AssertJ.
- `containsExactly` works on `entityList`; `isEqualTo(list)` and `containsOnly`
  do not.
- Tests must not depend on the network. A probe test points at a `.invalid`
  host, which can never resolve.
- Surefire needs `useManifestOnlyJar=false` here: without spring-boot-starter-parent
  the classpath goes through a manifest-only jar and Spring never finds
  `application.yml`. The suite also runs with `orknux.temporal.enabled=false`,
  so a run happens on the calling thread, `orknux.slack.enabled=false`, so no
  test opens a websocket to Slack, `orknux.model.check.enabled=false` and
  `orknux.connection.check.enabled=false`, so no sweep calls anything on a timer
  — `ModelAPITest` builds the monitor and calls `sweep()` itself — and
  `db-scheduler.enabled=false`, so no clock fires while a
  suite is running — `TriggerSchedulerTest` calls the tick itself. It also sets a
  fixed `orknux.security.secret-key`, because the suite stores credentials and
  they are encrypted with it; a real key has no business in a build file, and the
  test database is thrown away anyway.
- **A test that waits must not really wait.** A parked step is waited out on the
  thread by the inline engine, so keep test waits to a second or two; the
  Temporal path is the one to test a long wait on, where
  `TestWorkflowEnvironment` skips the timer and an hour costs nothing
  (`ExecutionWorkflowTest`).
- A stale `target/test-classes/application.yml` shadows the real config; if the
  datasource suddenly cannot be determined, run `clean`.
- **The suite has its own database.** `TestDatabase` is a JUnit
  `LauncherSessionListener`, found through `META-INF/services`, that starts a
  Postgres container and points `spring.datasource.*` at it before the first
  Spring context is built. Registered there rather than on each test class, so a
  test added later cannot forget to opt in and quietly wipe development data —
  which is exactly what `deleteAll()` in the fixtures would do.
  - Do not try to arrange this with `context.initializer.classes`: Spring Boot 4
    dropped `DelegatingApplicationContextInitializer`, so the property is read by
    nothing, no container starts, and the suite silently runs against the
    development database while looking like it worked.
  - Testcontainers 2 is what Boot's BOM manages, and the module is
    `testcontainers-postgresql` — not the `postgresql` of every 1.x example.
    `junit-platform-launcher` needs declaring too; surefire only supplies it at
    runtime, so compiling the listener against it fails without.
  - A canary row in the development database, checked after a run, is the only
    thing that actually proves the isolation. Assume nothing here.
