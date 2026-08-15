# Working in gyloli-server

Notes for anyone — human or agent — changing this repository. See
[README.md](README.md) for what the service is and how to run it.

## Commands

```
docker compose up -d                     # postgres, openldap and temporal
./mvnw spring-boot:run -pl app           # http://localhost:8080
./mvnw test                              # every module
./mvnw test -Dtest=IntegrationAPITest    # one class
```

Three Maven modules: `app` (the deployable), `modules/connection` and
`modules/execution`. Neither module may depend on `app`; where one needs
something the app owns it declares an interface — `TeamDirectory`,
`WorkflowGraphSource` — and the app implements it.

`spring-boot:run` forks a JVM; stopping the Maven process can leave it holding
port 8080. Kill the process whose command line contains `GyloliServerKt`.

## Stack quirks worth knowing

- **Spring Boot 4 ships Jackson 3.** The Jackson 2 Kotlin module does not apply,
  so DTOs bound from JSON need explicit `@JsonCreator` / `@JsonProperty`
  (`SessionAPI` shows the pattern). GraphQL inputs are unaffected.
- **`TestRestTemplate` is gone.** HTTP tests use `RestClient` with
  `defaultStatusHandler({ true }, { _, _ -> })` so error responses can be
  asserted on, plus `@LocalServerPort`.
- **Optional filters use JPA `Specification`s**, not JPQL. `:enum IS NULL OR …`
  fails in Hibernate 6; `TeamAuditRepository.auditFilter` is the shape to copy.
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

Module tables carry no foreign keys across a module boundary, so a deleted team
is reported to the module rather than cascaded.

## Conventions

- One `@Controller` per aggregate, with `@QueryMapping` / `@MutationMapping`
  methods and the DTOs, page wrapper and exceptions in the same file. An
  `…ExceptionResolver` maps those exceptions to GraphQL error types.
- **Every resolver checks access first.** `access.requireAdmin()` for
  organization-wide work, `access.requireVisible(team)` for anything team-scoped.
  A resolver that loads by id resolves the owning team and checks that.
- **Every change writes an audit entry**, worded as the UI shows it
  ("MCP Server brave-search added"), with the right category.
- **The modules hold no notion of a user.** A controller in `app` checks access,
  calls the module service, and records the audit entry — in that order. A module
  class that wants to know who is asking has a design problem.
- **GraphQL lives in `app` only.** The modules expose services; the schema, the
  controllers and the error mapping are the app's.
- **Wiring is the app's job**: `GyloliServer` scans, entity-scans and
  repository-scans `io.mszymanski.gyloli`, because a module cannot register
  itself into a context it does not own.
- **Credentials are read in one place.** `ConnectionTarget` resolves them for
  outbound calls; nothing else should touch a `secret` field. A Slack connection
  also holds an `appToken`, which is what Socket Mode opens the websocket with.
- **What arrives is published, not called.** `modules/connection` opens the
  sockets and knows nothing of triggers, so `SlackListener` publishes an
  `IncomingEvent` and `IncomingTriggerListener` in `app` decides what runs. The
  two `…Action` enums are matched by name, and a test holds them together.
- **A team's JavaScript is hostile input.** It runs through `ScriptRunner` and
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
  once per team and used by pointing a node at one. A node stores the id and
  nothing else: what the thing does belongs to the catalogue entry, and a run
  keeps its own copy of which entry it used.
- **A trigger is a catalogue entry, not a binding.** `workflow_trigger` names no
  workflow; a `workflow_node` of kind `TRIGGER` carries `trigger_id`, and that
  instance is the wiring. An arriving event matches definitions first and finds
  their instances second — `IncomingTriggerListener` shows both halves.
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
  `application.yml`. The suite also runs with `gyloli.temporal.enabled=false`,
  so a run happens on the calling thread, `gyloli.slack.enabled=false`, so no
  test opens a websocket to Slack, and `db-scheduler.enabled=false`, so no clock
  fires while a suite is running — `TriggerSchedulerTest` calls the tick itself.
- A stale `target/test-classes/application.yml` shadows the real config; if the
  datasource suddenly cannot be determined, run `clean`.
- `deleteAll()` in a test fixture also clears development data — re-seed before
  looking at the UI.
