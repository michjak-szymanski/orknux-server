# gyloli-server

Gyloli is fully open source, team based, agent orchestration platform.

A Kotlin/Spring Boot GraphQL API over Postgres, with sign-in against LDAP. One
deployable, built from modules that cannot reach into each other:

```
gyloli-ui ──▶ app ──┬──▶ connection ──▶ Slack, Jira, GitHub, Teams
                    └──▶ execution  ──▶ Temporal
```

| module               | owns                                                         |
|----------------------|--------------------------------------------------------------|
| `app`                | Teams, workflow definitions, agents, sign-in, the audit log, and the GraphQL API the browser talks to |
| `modules/connection` | Connections, MCP servers and every credential                 |
| `modules/execution`  | Runs: the engine, the Temporal worker, and what each run did  |

The modules are separate Maven artifacts, so the compiler enforces the boundary:
neither may depend on `app`. Where one needs something the app owns, it declares
an interface and the app implements it — `TeamDirectory` for the teams a backfill
reaches, `WorkflowGraphSource` for the graph a run is given. That is also the
seam to pull on if one of them ever has to become its own service.

[gyloli-ui](../gyloli-ui) is the React front end, and talks only to this service.

## Running

```
docker compose up -d              # postgres, openldap and temporal
./mvnw spring-boot:run -pl app    # http://localhost:8080
```

The first build has to be online, and `-pl app` builds the modules it needs.
Temporal's own UI is on http://localhost:8233, for looking at a run that went
wrong.

Flyway migrates the schema on start; JPA runs with `ddl-auto: validate`, so the
migrations are the only thing that changes the database. One process means one
database and one migration history, in `app/src/main/resources/db/migration`.

Sign in with a directory user from `docker/ldap/bootstrap.ldif`:

| user    | password   | groups                          |
|---------|------------|---------------------------------|
| `alice` | `password` | `admins`, `users`               |
| `bob`   | `password` | `users`, `backend`              |

These are development fixtures. The LDAP admin is `cn=admin,dc=gyloli,dc=io` /
`admin`, and Postgres is `gyloli` / `gyloli`.

## Tests

```
./mvnw test                            # every module
./mvnw test -Dtest=IntegrationAPITest  # one class
```

The tests are `@SpringBootTest` against the compose Postgres and LDAP, so bring
those up first. They run with `gyloli.temporal.enabled=false`, so a workflow runs
on the calling thread and no Temporal server is needed; the Temporal path has its
own test, which brings up an in-process environment. The suites clear the tables
they use, which also clears development data.

## How it is put together

| package in `app` | what lives there                                                      |
|------------------|------------------------------------------------------------------------|
| `security`       | Session endpoint, team visibility, the configurable admin role         |
| `ldap`           | Bind authentication and the group-to-authority mapping                 |
| `team`           | Teams and the audit log every other package writes to                  |
| `workflow`       | Workflow definitions, the editable graph, and the API over runs        |
| `agent`          | Agents and the MCP servers they may use                                |
| `integration`    | The integration API over the connection module                         |
| `trigger`        | The trigger catalogue, the listener, and the clock that fires the scheduled ones |
| `action`         | The action catalogue, the team's JavaScript functions, and the runtime for an action node |
| `condition`      | The condition catalogue, what decides one, and the condition node |
| `monitoring`     | The health of the service and everything it needs to be up             |

The GraphQL schema is `app/src/main/resources/graphql/schema.graphqls`;
controllers are `@Controller` classes with `@QueryMapping` / `@MutationMapping`.

### Access

`gyloli.security.admin-role` (default `ROLE_ADMINS`) names the role that sees the
organization section and every team. Everyone else sees a team only if they
belong to the directory group named on it: `cn=backend,ou=groups,…` grants
`ROLE_BACKEND`, and `TeamAccess` checks that on every read and write. A team with
no group is administrators-only. Group lookup needs
`gyloli.ldap.group-search-base` to point at the OU holding those groups.

### Audit

`TeamAuditRecorder` writes one row per change, attributed to the LDAP uid of the
caller. Entries carry a category (`TEAM`, `WORKFLOW`, `AGENT`, `INTEGRATION`) and
a message ready to display. An entry with no team is an organization-wide change
and only appears in the organization audit log.

### Where the modules meet

A controller in `app` checks access, calls the module, and records the audit
entry — in that order. The modules hold no notion of a user and never check one:
they cannot, and the check belongs where the directory groups are.

Their tables are their own. `team_connection.team_id` has no foreign key to
`team`, because that table belongs to another module, so a deleted team is
reported rather than cascaded — `TeamLifecycleService.forgetTeam`.

Everything runs in one process and one transaction manager, but the modules are
still told about each other's lifecycle events rather than reaching across, which
is what keeps splitting them out again cheap.

### Integrations

Administrators define default connections; every team created afterwards is
provisioned with a copy it can hold credentials against, and the check
(`testTeamConnection`) reports what the service actually answered rather than
whether a credential was typed in. Credentials are never returned by a listing —
revealing one is a mutation, and it is audited.

Secrets are stored as plain columns. They want envelope encryption or an external
secret store before this runs anywhere but a development machine.

### Actions and functions

An **action** is a reusable block a workflow is built from, defined once in the
team's catalogue: send something through a connection, call an HTTP endpoint,
call one of the team's functions, or wait — for a condition, or for a time. A
workflow uses one by pointing an **action node** at it, the same arrangement as
triggers.

What an action needs and what it produces are not stored. They are read off its
settings, so a `{{input.name}}` typed into the content is an input the moment it
is typed, and a function action's output follows the function's return type.

A **function** is JavaScript a team wrote, a module whose default export is
called. It runs in GraalJS with the sandbox `ScriptRunner` builds:

- no host classes, no class loading, no `Java`, `Packages` or `Polyglot`
- no files, no network, no threads, no processes, no environment
- no `load`, no `print`, and no timers, so nothing can be pending when a call
  returns
- a statement limit and a wall-clock timeout, either of which stops a script
  that will not finish
- a fresh context per call, so two runs cannot see each other

Everything crossing the boundary is JSON text; nothing the script touches is a
live Java object. `ScriptRunnerTest` is where those are held.

Only the function and wait actions have a runtime today. An outgoing connection
or an HTTP request records that it was not performed rather than claiming it
was.

### Connecting nodes

Every node reports what it needs and what it hands on — its **ports** — and an
edge is sound when what flows into a node covers what that node needs. The rules
are generic: `GraphValidator` knows nothing about particular kinds, and the kinds
differ only in how their ports are worked out.

Ports are derived, never stored. A node keeps the id of the catalogue entry it
uses and nothing else, so editing an action changes what its nodes need at once;
a copy on the node would be a second truth that goes stale. A trigger produces
its payload's fields plus what fired it, an action's ports are read off its
settings, a condition needs whatever it asks about, and a wait or a condition
hands on what it was given as well.

Two shapes are refused when saving, because they could never run: something
feeding a trigger, and something following a publish task. Everything else comes
back as advice on the graph — a node needing what nothing produces, a node with
nothing before it, a node with nothing chosen — because a workflow is drawn
before it is finished, and the editor lists them beside the canvas.

### Conditions

A condition is a question a team asks about what a run is carrying, defined once
and used from three places: a wait that holds until it holds, an action that
waits on it, and a **condition node**, which stops the run when the answer is no.
Stopping is not failing — the workflow asked and acted on the answer — so the run
finishes completed, with where and why recorded on the run itself
(`stopped_at_node_key`, `stopped_reason`). Otherwise it would be indistinguishable
from a run that did everything: the executions list marks it **Stopped early**,
the run detail says which node ended it and what it said, and the steps after it
read "Not reached" rather than "Pending".

A condition is data rather than code: a type (Slack, Jira, Time), a property, a
check, and what to check against, with a Negate switch that turns the answer
round — which is what makes "Is External User" the same definition as "Is
Teammate Message" negated. Two kinds are made of other things: **Any Of** and
**All Of** combine conditions, and **Function** calls one of the team's
functions, which has to return a boolean.

What a condition means in words is not stored. The list's description and the
sentence under the builder are read off the definition, so they cannot drift
from what will actually be asked.

### Triggers

The Triggers screen is a team's **catalogue**: each entry describes an event —
one arriving on a connection, or a cron expression — and names no workflow. A
workflow picks one up in the editor, by pointing a trigger node at a definition;
that node is the **instance**, and it is what wires the event to that workflow.
One definition can be instanced by several workflows, and an entry nobody
instances starts nothing.

A trigger can also carry a **payload**: a JSON object handed to the runs it
starts. The clock carries no data, so without one a scheduled workflow is handed
nothing but the cron expression and a function called from it has nothing to
work on. An incoming trigger puts its payload underneath what arrived, so the
event wins where both name a field.

Both kinds run. An incoming one fires when its event arrives; a scheduled one
fires on its cron expression, in its timezone, from a db-scheduler task that
ticks once a minute — which is what makes a schedule survive a restart and fire
once however many instances are up. The workflows list shows both ends of that:
when each workflow last ran, and when the clock will start it next.

Slack arrives over **Socket Mode**, a websocket gyloli dials out on, so a
self-hosted installation needs no public URL and no inbound rule. Add a
connection of type **Slack (Socket Mode)** — its form asks for the two
credentials the listener uses, a bot token (`xoxb-…`) and an app-level token
(`xapp-…`) — and `SlackListener` opens a socket for it within
`gyloli.slack.reconcile-seconds`;
an `app_mention` then matches every enabled definition watching that connection
for a mention, and runs each workflow instancing one, with the message, channel
and thread handed to the run. Set
`gyloli.slack.enabled: false` to open no sockets at all.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
