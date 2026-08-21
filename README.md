# orknux-server

[![CI](https://github.com/michjak-szymanski/orknux-server/actions/workflows/ci.yml/badge.svg)](https://github.com/michjak-szymanski/orknux-server/actions/workflows/ci.yml)
[![Licence](https://img.shields.io/github/license/michjak-szymanski/orknux-server?label=licence)](LICENSE)
[![Docker](https://img.shields.io/docker/v/orknux/orknux-server?label=docker&sort=semver)](https://hub.docker.com/r/orknux/orknux-server)
[![Image size](https://img.shields.io/docker/image-size/orknux/orknux-server/latest?label=image)](https://hub.docker.com/r/orknux/orknux-server)

Orknux is a fully open source, workspace based, agent orchestration platform.

[**orknux.io**](https://orknux.io) &nbsp;·&nbsp; [Documentation](https://orknux.io/docs)
&nbsp;·&nbsp; [Marketplace](https://orknux.io/market) &nbsp;·&nbsp;
[Docker Hub](https://hub.docker.com/r/orknux/orknux-server)

The same site answers to [orkx.io](https://orkx.io), [orknux.ai](https://orknux.ai)
and [orkx.ai](https://orkx.ai). `orkx` is the short form, kept for links and the
command line; `orknux` is the name.

A Kotlin/Spring Boot GraphQL API over Postgres or SQLite, signed in against a
directory or an OIDC provider. One deployable, built from modules that cannot
reach into each other:

```
orknux-ui ──▶ app ──┬──▶ connection ──▶ Slack, SMTP, HTTP endpoints
                    └──▶ execution  ──▶ Temporal
```

| module               | owns                                                         |
|----------------------|--------------------------------------------------------------|
| `app`                | Workspaces, workflow definitions, agents, sign-in, the issue tracker, the audit log, and the GraphQL API the browser talks to |
| `modules/connection` | Connections, MCP servers, LLM providers, and every credential |
| `modules/execution`  | Runs: the engine, the Temporal worker, and what each run did  |

The modules are separate Maven artifacts, so the compiler enforces the boundary:
neither may depend on `app`. Where one needs something the app owns, it declares
an interface and the app implements it — `WorkspaceDirectory` for the workspaces a backfill
reaches, `WorkflowGraphSource` for the graph a run is given. That is also the
seam to pull on if one of them ever has to become its own service.

[orknux-ui](https://github.com/michjak-szymanski/orknux-ui) is the React front
end, and talks only to this service.

## What it looks like

The interface's own pictures, taken by its capture script against a workspace
built to be photographed — so they are the interface as it is now rather than
the one somebody screenshotted once. They are linked out of that repository
rather than copied here: it is a submodule, and GitHub renders a submodule as a
link, not as files it will serve an image from.

![A workflow on the canvas: the graph, what each edge carries, and the selected
node's settings](https://raw.githubusercontent.com/michjak-szymanski/orknux-ui/main/public/screens/editor.png)

*A workflow. Edges are labelled with the fields that travel along them.*

![A run: its summary, and the graph as it ran, each node marked with what it
did](https://raw.githubusercontent.com/michjak-szymanski/orknux-ui/main/public/screens/execution-detail.png)

*A run, node by node — what each step was given, and what it returned.*

![A chat with one of the workspace's models](https://raw.githubusercontent.com/michjak-szymanski/orknux-ui/main/public/screens/chat.png)

*Chat, against whichever model the workspace is pointed at.*

![The issue tracker inside a workspace, with labels, assignees and
filters](https://raw.githubusercontent.com/michjak-szymanski/orknux-ui/main/public/screens/issues.png)

*The tracker every workspace has. The MCP endpoint below speaks to this one, so
an agent can file and read issues without a browser.*

![Monitoring: each component with its version, and what it is
answering](https://raw.githubusercontent.com/michjak-szymanski/orknux-ui/main/public/screens/monitoring.png)

*What is running, and whether it is answering.*

The rest — the catalogues, the admin section, the command palette — are in
[the manual](https://orknux.io/docs).

## Running

Two things go by that name here, and neither is a smaller version of the other.

### To run Orknux

One file, the published images, nothing built from source. It brings up the
server, the interface, Postgres, a directory to sign in against and Temporal:

```
curl -O https://raw.githubusercontent.com/michjak-szymanski/orknux-server/main/deploy/compose.yaml
export ORKNUX_SECRET_KEY="$(openssl rand -base64 32)"
docker compose up -d                 # http://localhost:8080, sign in as alice / password
```

[deploy/README.md](deploy/README.md) is the whole of it: what each service is
for and whether it is genuinely required, which image tags to pin to, where the
data lives, and what to change before it is anything more than a demonstration.
Read the part about `ORKNUX_SECRET_KEY` before you save a credential rather than
after.

### To look at Orknux

One container, no file to copy and nothing to configure. The interface, the
server and a SQLite file, with a key and an administrator generated on the first
start and the password printed in the log:

```
docker run -d --name orknux -p 8080:8080 -v orknux-data:/var/lib/orknux orknux/orknux-one
docker logs orknux                   # http://localhost:8080, and the password to use
```

**It is not a deployment.** There is no Temporal, so a run is not durable, is not
retried and does not resume; a wait longer than five minutes fails by design;
there is no directory and no OIDC; and SQLite means one writer, one process and
one machine. [DOCKERHUB-ONE.md](DOCKERHUB-ONE.md) is the full list, and
[deploy/README.md](deploy/README.md) says where the line between this and a
deployment is. It is built from [Dockerfile.one](Dockerfile.one), which merges
this repository's `Dockerfile` with the interface's own image rather than
describing either again.

### To work on Orknux

The compose file at the root of this repository is a different one. It brings up
only the dependencies, because the server itself is expected to be running from
Maven on the host, and it publishes their ports so that it can:

```
docker compose up -d                 # postgres, openldap and temporal
./mvnw spring-boot:run -pl app -am   # http://localhost:8080
```

The first build has to be online. `-am` is what builds the modules alongside
the app: without it they are resolved from the local repository instead, so a
change in one of them is invisible until something installs it — and the build
fails on a symbol that is plainly there in the source.
Temporal's own UI is on http://localhost:8233, for looking at a run that went
wrong.

**`ORKNUX_SECRET_KEY` has to be in the environment it is started in** — 32 bytes,
base64, from `openssl rand -base64 32`, and the same one every time or the
credentials already stored cannot be read. There is deliberately no default. It
is read on first use rather than at startup, so a server without it starts
perfectly, reports itself healthy, and fails the first time anything reads or
writes a credential; the Doctor page under Admin is what says so before somebody
trips over it.

### The front end

That serves the API. The app you sign in to is
[orknux-ui](https://github.com/michjak-szymanski/orknux-ui), its own repository,
carried here as a submodule so a clone pins the front end this server was
built against:

```
git clone --recurse-submodules https://github.com/michjak-szymanski/orknux-server
cd orknux-ui
docker compose up dev             # http://localhost:5173
```

An existing clone that predates it wants `git submodule update --init`. The
submodule tracks `main`, so `git submodule update --remote orknux-ui` moves the
pin forward, and the move is a commit here like any other.

Node is not needed on the machine — the toolchain runs in the container. The dev
server proxies `/api` and `/graphql` to this service on 8080 (override with
`ORKNUX_SERVER_URL`), so the browser stays on one origin and the session cookie
is first-party. Open http://localhost:5173 and sign in with a directory user
from the table below; going to 8080 directly gets you the API, not the app.

### Checking the image

```
scripts/verify-image.sh           # builds it, starts it, asserts it works
scripts/verify-one-image.sh       # the same for orknux-one, started with nothing
```

The suite says the code behaves; this says the artefact runs. It builds the
image, brings it up against a real Postgres, and checks that it boots and
serves, that Flyway migrated, that anonymous callers are refused, that it runs
as `orknux` rather than root, that the JVM is PID 1, and that `docker stop`
reaches it rather than killing it. CI runs it between the suite and the publish,
so nothing reaches the registry unstarted.

It starts the image with `ORKNUX_TEMPORAL_ENABLED=false`, because whether a
separate service is reachable is not a property of this image. Worth knowing
while reading a green run: with the default configuration the application
**refuses to start** when Temporal is not up, which is deliberate — so a
deployment brought up before its Temporal restarts until that service answers.

`verify-one-image.sh` asks a different set of questions of `orknux-one`, because
that image's claim is that it needs nothing: it is started with no environment
and no configuration at all, and the script then checks that it generated an
encryption key and kept it, that the administrator it made up and printed can
actually sign in, that a signed-in call reads and writes, and that a restart is
the same installation rather than a new one — same key, same account, same data.
That last one is the failure worth a test: a second start that generated a second
key would boot, look healthy, and make every stored credential unreadable.

Flyway migrates the schema on start; JPA runs with `ddl-auto: validate`, so the
migrations are the only thing that changes the database. One process means one
database and one migration history, in
`app/src/main/resources/db/migration/postgresql`. **The database** below says what
the other one is.

Sign in with a directory user from `docker/ldap/bootstrap.ldif`: 

| user    | password   | groups                          |
|---------|------------|---------------------------------|
| `alice` | `password` | `admins`, `users`               |
| `bob`   | `password` | `users`, `backend`              |

These are development fixtures. The LDAP admin is `cn=admin,dc=orknux,dc=io` /
`admin`, and Postgres is `orknux` / `orknux`.

## The database

Postgres or SQLite, chosen by the connection URL and nothing else:

```
ORKNUX_DB_URL=jdbc:postgresql://localhost:5432/orknux     # a server
ORKNUX_DB_URL=jdbc:sqlite:/var/lib/orknux/orknux.db       # a file
```

Everything downstream follows from that one line - the driver, the Hibernate
dialect, and which migrations Flyway reads. Under SQLite the username and
password are ignored, since a file has nobody to authenticate to.

**Postgres is what a deployment should use.** It takes more than one writer, it
is what the tests run against by default, and it is where any behaviour under
load has actually been watched. SQLite is there for the installation of one or a
few: a single file, nothing else to run, nothing to back up but that file.

The schema is Flyway's either way, and JPA runs with `ddl-auto: validate`, so
migrations are the only thing that ever changes it. The two are not two spellings
of one history. Postgres has every migration since the first release, under
`db/migration/postgresql`. SQLite has a single baseline saying what they all add
up to, under `db/migration/sqlite`, because a third of the history is `ALTER`
statements SQLite has no equivalent for and there is no SQLite installation old
enough to need replaying onto. **A change to the schema has to be written twice**
- a numbered migration for Postgres, the same change folded into the baseline for
SQLite - and `SqliteSchemaTest` is what notices when only one of them was, with
`SqliteCheckConstraintTest` covering the `CHECK` constraints that schema
validation looks straight through.

### What is different on SQLite

Everything the test suite covers works: signing in, workspaces, issues, agents,
workflows, executions, chat and its history, the MCP endpoint, attachments,
sessions, password resets, proxy rules and the shells an agent runs commands on.
The same suite runs against both, switched by a property rather than written
twice — **Tests** below says what that run is worth at the moment. What differs
is underneath.

**One writer at a time.** This is the whole of it, and everything below is a
consequence. SQLite takes a single write lock for the database, so two requests
that both write are serialised rather than run together. WAL journalling is
turned on so readers are not blocked by a writer, connections wait up to thirty
seconds for their turn rather than failing, and transactions take the write lock
when they open rather than discovering they need it later - without that last
one, a transaction that read before it wrote fails immediately and is not
retried. It is quick enough for a handful of people and it is not a database to
run a busy installation on.

**One machine.** The file is the installation. Two instances of the server
pointed at one file over a network share will corrupt it - WAL does not work
across a network filesystem - so SQLite means exactly one process, and with it no
rolling restart and no second node.

**No time zones.** SQLite has no zoned timestamp type, so a moment is stored as a
moment and read back in the server's own zone. Instants are preserved and
compare correctly; the original offset is not kept. Nothing in the interface
shows an offset, so this is invisible until something outside reads the file.

**Foreign keys are on because this turns them on.** SQLite has them off by
default per connection, which would make every `ON DELETE CASCADE` in the schema
a comment.

**The scheduler needs telling about it.** db-scheduler ships a dialect for every
database it supports and none for SQLite; left alone it writes an `OFFSET ...
FETCH FIRST` clause SQLite refuses, fails every poll and fires no schedule ever.
`SqliteJdbcCustomization` is what makes it work.

**Sessions join the caller's transaction.** Spring Session would keep its own,
independent of whatever asked - correct on Postgres, and on SQLite a deadlock
against the request that called it. So on SQLite a session write rolls back with
the request that made it.

**Backups are a file copy, and only when nothing is writing.** There is no
`pg_dump` equivalent here; copy the database and its `-wal` file together, or
stop the server first.

## Tests

```
./mvnw test                                  # every module, on Postgres
./mvnw test -Dtest=IntegrationAPITest        # one class
./mvnw test -Dorknux.test.database=sqlite    # the same suite, on SQLite
```

The tests are `@SpringBootTest` against **their own Postgres**, started as a
container for the run and thrown away with it, so a suite cannot touch the
database you are developing against. The fixtures clear the tables they use, and
`deleteAll()` cannot tell whose rows are whose — pointed at the development
database it takes the workspaces, models and chat history you were looking at.
`TestDatabase` starts the container before any Spring context exists; Docker has
to be running, LDAP still comes from compose.

`-Dorknux.test.database=sqlite` runs the same tests against a SQLite file in
`app/target` instead, and needs no Docker. It is a switch rather than a second
suite on purpose: a suite that only ever exercises one database will not notice
the day the other stops working.

**The SQLite run is not green at the moment** — issue #171 — and CI does not run
it, so it is a red that is already known about rather than something a checkout
did. It is the engine `orknux-one` ships with, which is why it is worth running
by hand rather than treating as the second-class half of the switch.

They run with `orknux.temporal.enabled=false`, so a workflow runs on the calling
thread and no Temporal server is needed; the Temporal path has its own test,
which brings up an in-process environment. `orknux.model.check.enabled=false` and
`orknux.connection.check.enabled=false` keep the sweeps from calling anything
while a suite runs, and a fixed `orknux.security.secret-key` is set in the build
so the credentials a test stores can be read back — the test database is thrown
away, and a real key has no business in a build file.

## How it is put together

| package in `app` | what lives there                                                      |
|------------------|------------------------------------------------------------------------|
| `security`       | Session endpoint, roles, workspace visibility, and the OIDC configuration |
| `ldap`           | Bind authentication and the group-to-authority mapping                 |
| `user`           | Everybody this installation knows, internal and external, their passwords and their access tokens |
| `workspace`           | Workspaces and the audit log every other package writes to                  |
| `workflow`       | Workflow definitions, the editable graph, the publications, and the API over runs |
| `transfer`       | Moving components between installations: the JSON envelope, what it refuses to carry, and the templates an installation keeps |
| `revision`       | What a function, tool, skill or agent was before its last save, the restore that puts one back, and the sweep that ages them out |
| `agent`          | Agents, the MCP servers they may use, the tools they may call and the skills that guide them |
| `integration`    | The integration API over the connection module                         |
| `trigger`        | The trigger catalogue, the listener, and the clock that fires the scheduled ones |
| `action`         | The action catalogue, the workspace's JavaScript functions, and the runtime for an action node |
| `condition`      | The condition catalogue, what decides one, and the condition node |
| `obj`            | The shapes a workspace's workflows pass around, and the node that makes one |
| `variable`       | The workspace's own values and secrets, and how a function is handed them |
| `chat`           | Chats, and the Spring AI conversation each one is |
| `attachment`     | Files a chat or an issue carries, where the bytes go, and the installation switches over both |
| `issue`          | The workspace's issue tracker: issues, comments, mentions, and the feed a bell and an assistant read |
| `memory`         | Memory catalogs, the notes in them, and the tool an agent reads them through |
| `model`          | The API over the workspace's LLM providers, the models reached through them, and what they were used for |
| `llm`            | LLM sessions: a conversation an agent keeps across the runs that share its key, and the transcript of what was said and called |
| `mcp`            | The MCP endpoint this server serves, and the tools an outside assistant calls through it |
| `shell`          | The tool an agent runs commands through, on the machines Admin -> Shell holds; the sessions themselves belong to `modules/connection` |
| `mail`           | The installation's own relay, and the one thing it sends: a password reset link |
| `database`       | What SQLite needs and Postgres does not - the dialect, the pragmas, and the SQL the scheduler ships no dialect for |
| `plugin`         | Plugins loaded into the installation, and the functions they declare |
| `monitoring`     | The health of the service and everything it needs to be up             |
| `admin`          | The Doctor: whether this installation is configured correctly, which is not the same question as whether it is up |

The GraphQL schema is `app/src/main/resources/graphql/schema.graphqls`;
controllers are `@Controller` classes with `@QueryMapping` / `@MutationMapping`.

### Access

A **role** is this installation's own word for an audience, and it is the only
vocabulary anything past the front door deals in. A role has a scope: `ADMIN`
sees the Admin section and every workspace, `USER` sees the workspaces the role
is assigned to. A workspace names the roles that open it, and `WorkspaceAccess`
checks them on every read and write; a workspace naming none is
administrators-only, which is the safe direction to fail in.

What the identity provider calls things is translated once, by `RoleResolver`.
`orknux.security.role-mapping` maps a group DN or an OIDC claim value onto a
role by name, and a role is also granted to whoever holds the authority derived
from its own name — a role called `Backend` to anyone holding `ROLE_BACKEND` —
so an installation that was using directory groups keeps working with nothing
written. `orknux.security.admin-role` (default `ROLE_ADMINS`) still administers
on its own. Group lookup under LDAP needs `orknux.ldap.group-search-base` to
point at the OU holding the groups.

`orknux.security.auth-method` picks `LDAP`, `OIDC` or `INTERNAL`, one at a time —
the last being no directory and no provider at all, which is what `orknux-one`
runs on. Beside any of them there are **internal users**: identities this
installation made up, which may be given a password and may mint access tokens.
A token is 32 random bytes
behind an `orkx_` prefix, kept only as a SHA-256 hash, presented as
`Authorization: Bearer orkx_…`, and it creates no session. That is how something
that is not a browser — the CLI, or an assistant calling the MCP endpoint — signs
in. `POST /api/session` tries an internal password first, so those accounts work
on an OIDC installation too.

**The first internal user is the one nothing can make.** Every account either
comes from an administrator or is written down when a provider vouches for
somebody at the door, so an installation with no directory and no OIDC provider
has nobody to create the administrator who could create you.
`ORKNUX_BOOTSTRAP_ADMIN_USERNAME` and `ORKNUX_BOOTSTRAP_ADMIN_PASSWORD` close
that circle: set both and one internal administrator is created at startup,
holding the built-in `Administrators` role and signing in on the ordinary form.
`BootstrapAdmin` only ever creates - an account of that name that already exists
is left exactly as it is, password and roles alike, so a variable still set on
the tenth restart cannot put back a password somebody changed or a role somebody
deliberately took away. A password in a variable is a way in and not a credential
to keep: sign in, change it, unset both. With SQLite underneath, that is an
Orknux with no database server and no directory to run.

`POST /api/session` is throttled per username and per address alike -
`orknux.security.sign-in` - because a username somebody knows exists could
otherwise be tried at whatever rate the network allowed, and under LDAP every
try landed on the directory too. It backs off rather than locking: the wait
doubles to a ceiling and stops, a success clears the record, and going quiet
forgets it. An account that locks is one a stranger can close by guessing at it
badly on purpose.

**A forgotten password is reset by a link mailed to the address on the account**,
good once and for an hour, and only for an internal user who already has one - a
directory or OIDC account's password belongs to the provider. It needs the
installation's own mail relay (`orknux.mail`) and `orknux.web.base-url`, and
without them the form answers the same polite sentence to everybody and the log
says what is missing. The relay is an operator's setting rather than a
workspace's SMTP connection on purpose: that credential belongs to one team,
would stop working the day they rotated it, and a reset has to work for somebody
who belongs to no workspace at all. The base URL is configured rather than read
off the `Host` header because that header is written by whoever is calling, and
the link in question opens an account.

Everybody who signs in is recorded: `UserDetection` writes an `EXTERNAL` row the
first time somebody arrives, which is what makes an issue assignable to a person
without the application enumerating a directory it was never given permission to
read.

### Audit

`WorkspaceAuditRecorder` writes one row per change, attributed to the username of the
caller. Entries carry a category (`WORKSPACE`, `WORKFLOW`, `AGENT`, `INTEGRATION`,
`MODEL`, `MEMORY`, `OBJECT`, `CHAT`, `SHELL`) and
a message ready to display. An entry with no workspace is an admin-level change
and only appears in the admin audit log.

### Where the modules meet

A controller in `app` checks access, calls the module, and records the audit
entry — in that order. The modules hold no notion of a user and never check one:
they cannot, and the check belongs where the roles are.

Their tables are their own. `workspace_connection.workspace_id` has no foreign key to
`workspace`, because that table belongs to another module, so a deleted workspace is
reported rather than cascaded — `WorkspaceLifecycleService.forgetWorkspace`.

Everything runs in one process and one transaction manager, but the modules are
still told about each other's lifecycle events rather than reaching across, which
is what keeps splitting them out again cheap.

### Integrations

Administrators define default connections; every workspace created afterwards is
provisioned with a copy it can hold credentials against, and the check
(`testWorkspaceConnection`) reports what the service actually answered rather than
whether a credential was typed in. Credentials are never returned by a listing —
revealing one is a mutation, and it is audited.

A socket that opened is not a service that works, so the probe reads the status
the endpoint chose. A 2xx is a connection; 404 or 410 means nothing is served at
that URL and 5xx means the service is failing, both of which are failures however
cleanly the socket opened. The one nuance is the answers that mean "not like
that" rather than "not here" — 400, 405, 406, 415, 501 — which is what a
POST-only MCP server or webhook says to a HEAD. Only something listening there
can refuse that way, so it counts as reachable and the message says exactly that
instead of calling it success.

**A mail server is a connection too.** A connection of type **SMTP** carries a
host, a port, a login, the address to send from and how the session is secured,
and its password is the `secret` column every other credential already lives in —
a second password column would be a second one to remember to encrypt. Its check
is not a HEAD request, because that answers nothing about a mail server: it opens
a real SMTP conversation, negotiates, authenticates and closes.

Secrets are encrypted at rest with AES-256-GCM, keyed by
`orknux.security.secret-key` — 32 bytes, base64, and deliberately without a
default. `SecretConverter` puts every credential column through it on the way in
and out, and a stored value is recognisable by its envelope, `orkx1:iv:ciphertext`.
The key comes from the environment, so this defends a backup, a disk or a replica
and not somebody who can already read the application's own environment. Losing
it makes every stored credential unreadable; the Doctor page under Admin is where
to find out whether the key is set, the right length, and able to read what is
already there.

### Networking

Where this installation has to go out through a proxy is one list of **rules**,
installation-wide rather than per workspace: the reason a proxy exists is the
network this process sits in and not the team whose workflow made the call, and
it is the only scope that can cover the calls made for nobody in particular — the
token grant a model provider needs, the sweep that asks whether connections still
answer. A rule matches its pattern anywhere in the request URL, ignoring case,
and names a host and a port with an optional login whose password is encrypted
like every other credential; where two rules could answer, the order an
administrator put them in decides.

**Everything this product does over the network goes through them**, which is
worth saying because for a while three things did not. Signing in with OIDC is
four clients Spring Security builds for itself — discovery, the key set, the
token exchange, userinfo — and discovery runs while the server is starting, so a
proxied installation did not come up at all. Slack went out through its SDK's own
HTTP client and its own websocket stack. And mail is matched as
`smtp://host:port`, opening a `CONNECT` tunnel through the proxy a rule names.
A host a rule carries is also the **proxy's to resolve**: resolving it here first
is how an installation whose proxy configuration was entirely correct failed
every call before the rules were consulted at all. A host no rule carries is
still checked here, and an address pointed at instance metadata is still refused
whether or not a proxy would have fetched it.

Two things these rules cannot do, and the page says so rather than letting them
be found out. Directory sign-in over **LDAP** is not HTTP, so an HTTP proxy has
nothing to carry. And a websocket or a mail session is a `CONNECT` tunnel, which
a proxy has to be willing to open — if yours will not, that is where a rule that
looks right still fails.

### Chat

A chat is one conversation. The messages are not this application's: they live
in **Spring AI's chat memory store**, keyed by a conversation id the session
holds, and `chat_session` carries only what that store has no opinion about —
who owns it, what it is called, which model answers, whether it is pinned.

That split is the point. A workflow run will key a conversation the same way,
so when an execution becomes a conversation every agent in that run reads and
appends to **one thread** rather than each keeping its own. Nothing about the
history is chat-screen-shaped.

Flyway creates `SPRING_AI_CHAT_MEMORY`, exactly as the starter declares it, and
`spring.ai.chat.memory.repository.jdbc.initialize-schema` is `never`: one
schema, one history of it.

Sending calls the chosen model through `ModelChatClient`, which lives in
`modules/connection` because it needs the credential. Two request shapes are
spoken — Anthropic's, and the OpenAI chat-completions shape that Azure OpenAI,
Ollama and most self-hosted servers also answer. What a provider refuses is
reported in the provider's own words. A chat with no model chosen is given the
workspace's first active one, because a chat that cannot be sent to is not worth
opening.

**The answer arrives as it is written.** A model composes over seconds, a large
local one over minutes, and a mutation can only return once it has finished —
which is a blank screen for the whole of that time. `POST /api/chats/{id}/stream`
sends `chunk`, `done` and `error` as server-sent events, reading the provider's
own stream and speaking both delta shapes. It is the one part of the chat that is
not GraphQL: the browser client here is `fetch`, and a subscription would mean
adding a websocket transport and the `graphql-ws` protocol to send one string.

**A chat can be with an agent rather than with a bare model.** An agent is a
configuration — the model that answers, the instructions it works under, and the
skill catalogs it has been granted — so handing a chat to one makes its model the
chat's model, and puts a system turn in front of the conversation holding its
prompt and the skills in the catalogs it was given. Only those: a skill in a
catalog nobody granted is the workspace's, not that agent's, and a skill switched
off is out of reach here as anywhere.

**An agent with tools does not answer in one round.** It asks for a lookup, is
told what came back, and either asks again or answers; `AgentConversation` runs
that to a conclusion and hands back what was finally said. Both request shapes
are spoken here too — OpenAI puts tool calls on the message and answers them in a
turn of their own, Anthropic makes both a block among the content — and the
intermediate turns are deliberately not written to the history. What is kept is
the conversation somebody had; that an agent read three skills on the way to an
answer is how it worked, not what was said, and keeping it would mean every later
round pays for it again. Eight rounds is the limit, after which the run is stopped
and says so rather than being billed in a loop.

Three tools are built in, offered only where the grant makes them useful:

- **`skill_list`** names the skills the agent was given and what each is for.
- **`skill_load`** reads one in full, by name.
- **`memory_search`** looks through the memory catalogs it was granted.

They are built in rather than being workspace tools because a workspace tool is
JavaScript in a sandbox with no IO — it cannot read a table, and widening the
sandbox so it could would be a hole opened for one feature.

Two more sources join them. An agent granted **access to orknux** is offered the
same `orknux_*` tools the MCP endpoint serves, scoped to its own workspace and no
wider: the grant is the authorisation, and there is no session here to ask about
anything else. And whatever the **MCP servers** it was granted say they offer,
asked of each server as the tools are assembled rather than read from a list
written down when somebody added it — a server that cannot be listed contributes
nothing rather than failing the conversation.

**The workspace's own tools are offered alongside them, under their own names.**
That is the opposite case: a tool *is* the workspace's code, and the sandbox is
where it belongs, so `ScriptRunner` runs it with the same limits as everything
else. The model is asked to put what the tool needs in `input`; a tool declares
no parameters, being a default export that takes what it is given, and its
description is what tells the model what belongs there. A tool named like a
built-in is not offered rather than shadowing it — two tools answering to one
name is a call nobody can predict the destination of.

Tools are granted per agent, like the catalogs, and this is the grant that
matters most: a skill is a page an agent reads, a tool is code it runs. An agent
granted none calls none, and a name the model invents resolves to nothing rather
than to code. A script that throws comes back as an error the model can read,
because it can then apologise, try another way, or answer without it — all of
which beat the conversation dying because a tool threw.

The briefing therefore *lists* skills rather than spelling them out. Each is a
page of markdown, and an agent granted five catalogs would spend most of its
context on instructions for work it is not doing, so it is given the names and
loads the one that applies. An agent granted nothing is handed no tools at all,
rather than tools that answer "nothing here" — that is a round trip spent
learning what the grant already said.

One consequence worth knowing: an agent chat does not stream. A round that asks
for a lookup produces no text worth showing, and what to say is only settled once
the loop ends, so the answer arrives in one piece.

A chat starts out called "New chat", and is renamed from what was actually said
once there is something to go on. The model that does it is the workspace's
**companion model**, chosen in workspace settings — a small job, and not one worth
spending the chat's own model on. A workspace that has not chosen one keeps the
placeholder rather than guessing.

Streaming is why `send` is split into `beginSend` and `finishSend`. The user's
turn is written before the model is called, the answer when it ends, and nothing
holds a database transaction open for the minutes in between — a transaction
open that long is a pooled connection nobody else can have.

### The tracker

Every workspace has an issue tracker. An issue has a **number** of its own within
its workspace — `#4` is what people say and what the URL carries, not a row id —
a title, a description in markdown, a status (`OPEN`, `IN_PROGRESS`, `CLOSED`),
free-text labels, and an assignee. What can be assigned is a person, an **agent**
or a **model**, because half the work here is done by something that is not a
person. There is no priority column: `p1` is a label, which is what stops one
workspace's idea of urgent from being fixed in the schema.

Comments are markdown, editable only by whoever wrote one — administrators
included — and they carry the time they were edited. Files attach to an issue or
to a comment, through the same store, the same switch and the same size limit as
a chat's attachments; only whoever attached a file may take it off. Turning
attachments off hides the controls and refuses the endpoint but leaves what is
already there readable, because switching uploads off is not the same as removing
evidence.

**Being told, rather than having to look.** `IssueNewsDesk` writes one row per
thing that happened — `ASSIGNED`, `STATUS`, `COMMENT`, `MENTIONED` — addressed to
whoever it concerns: an assignment tells the new assignee, a status change or a
comment tells the assignee and the reporter, and a name written into a comment
tells that person whether or not they had anything to do with the issue. Mentions
are matched against the names that exist, longest first, so `@Ann` does not match
inside `@Anna`.

Reading marks read, which is right for an assistant asking what it has missed and
wrong for a bell — the number would clear itself the moment it was drawn. So
there are two ways in: `myNotifications` looks without saying it looked, and
`readMyNotifications` is what says they have been seen.

### The MCP endpoint

This server is itself an MCP server: `POST /mcp/{workspaceId}`, JSON-RPC 2.0 over
plain HTTP, one endpoint per workspace so the workspace is in the URL rather than
in every call. There is no separate door — a caller authenticates the way
everything else does, with a session cookie or an `orkx_` token as a bearer, and
then `WorkspaceAccess` decides. A workspace that is missing or invisible is
answered inside the protocol rather than as an HTTP status, because a model
reading a transport error learns nothing from it.

The tools are `orknux_*`, and they are what lets an assistant work the tracker
rather than be told about it: list and read issues and their labels, comment, set
a status, change a title or its labels; list workflows, agents and functions, read
one run in full, start a workflow or run a past one again. `orknux_news` is the
same feed the bell reads — what has happened on the issues that concern you since
you last read — and it can be asked to hold the call open for up to five minutes
rather than be polled. Which tools are offered depends on the scope: reading is
always there, writing is offered where the caller may write.

### Tools and skills

What a workspace gives its agents to work with. A **tool** is JavaScript an agent may
call while it runs; a **skill** is markdown telling it how to go about something.

A tool is not a workflow function, though both are the workspace's JavaScript in the
same sandbox. The difference is who calls it: a function is called by an action
node, at a point the graph fixed in advance, and a tool is offered to an agent
that calls it if it judges that it should. That is also why a tool's description
matters — it is what the agent reads to decide.

A skill opens with a frontmatter fence naming and describing it, because a skill
is handed to an agent and has to say what it is without being read in full.
`SkillFormat` is where that is checked, and it is what the editor's Validate
reports; the tool editor's Validate is the same parser that would run the code.

Both can be turned off without being deleted, and both record who last saved
them, which the lists and the editors show.

**And both keep what they were.** Every save of a function, a tool, a skill or an
agent writes down the state it replaced, and each of the four has a **History**
panel beside it: who saved it, when, what the code or the prompt said then, and a
button that puts it back. Restoring keeps what it replaces too, so a restore is
undone the same way it was made. A version is a whole copy of what a component
was, which is why how long they are kept is an administrator's setting —
`ORKNUX_REVISION_RETENTION_DAYS`, fourteen days by default, measured from when a
version stopped being current rather than from when it was written. Nothing from
before the upgrade is recovered: a component's history begins with the first save
after it. A workflow is not one of the four because a workflow's versions are its
publications — see **Publishing**.

### Memory

A **memory catalog** is a folder of notes a workspace keeps: an incident writeup,
a runbook, whatever somebody wanted the agents to know. It is its own thing rather
than a label because it exists whether or not anything is in it, and because it is
the unit an agent is granted.

An agent reads memories through the built-in `memory_search` tool, and only from
the catalogs its editor granted it — a workspace can hold a catalog no agent can
see. Granting is per catalog rather than per memory: what an agent may know is a
decision worth making once, not once per note.

### Models

A workspace reaches models through **providers**, and a provider holds a key — which
is why it lives in `modules/connection` beside MCP servers: credentials are read
in one place. Each provider has a type, and the type decides what else it needs.

**A type exists where something branches on it**, and nowhere else. `OPENAI` is
the shape the rest are measured against, `ANTHROPIC` has its own body, streaming
events and `/messages` path, `AZURE_OPENAI` puts the deployment and the API
version in the URL, `OLLAMA` is the OpenAI shape at an address of your own — and
`CUSTOM` is anything that speaks one of those well enough, which is most things.
A service reached through **Custom** is not a second-class one: Google's own
OpenAI-compatible endpoint is a Custom provider pointed at
`https://generativelanguage.googleapis.com/v1beta/openai`, and a type of its own
would have branched on nothing but the name of an auth header.

Azure OpenAI wants an API version, a deployment and a region, and can
authenticate either with an API key or as an **Entra ID service principal**,
where the credential is not a key on the resource but a tenant, an app
registration and its secret, exchanged with Entra for a token. Those tokens are
kept for as long as Entra says they last, less a minute, and keyed on the whole
credential: a chat sending ten messages should not send ten grant requests to
Microsoft first, and rotating the secret must not go on using the old token.
`orknux.connection.entra-authority` moves the authority for the sovereign
clouds.

**Connected means checked.** A provider carries the same three status columns a
workspace connection does, and `testModelProvider` is what fills them: the key path
calls the endpoint with the header that type wants — `x-api-key`, `api-key`, or a
bearer — and the Entra path performs the client credentials grant, which is the
only thing that says whether the tenant, client and secret go together. Anything
that could change what a check would find forgets the last one, so a stored
answer never describes a provider that has moved.

What the check asks for is the **model list**, not a HEAD on whatever URL was
typed in. "Something answered" is a poor thing to call a connection — a host that
returns 404 for its model list is one the chat cannot use, and reporting that as
success is how you get told *"Connection successful — Answered with 404"*. A
listing proves three things at once: the host is reachable, the credential was
accepted, and the thing at the other end is a model API. So a good check says how
many models it found, and a 404 says the endpoint is wrong.

**Saving a provider checks it.** `ModelService` publishes `ModelProviderSaved`
and the monitor acts on it after the commit, on its own thread — after, because a
check in another thread would otherwise race the transaction that wrote the
endpoint it is about to call; on its own thread, because a form should not wait
on a provider that may be five seconds from timing out.

**And it is checked again without being asked.** A key is revoked, a local model
server is stopped, an endpoint moves — and a status recorded this morning goes on
saying "Connected" until somebody presses the button. `ModelProviderMonitor`
re-runs the same check on a timer (`orknux.model.check`, every five minutes by
default) over every provider that has something to check with; the ones that do
not are skipped rather than reported as failing, because not configured is not
broken. A failure to reach one provider cannot end the sweep. Set
`orknux.model.check.enabled=false` to check only on the button — which is what a
test run does.

The check's answer is also the catalogue: `discoveredModels(providerId)` runs the
same request and returns the ids the provider listed, flagging the ones already
added rather than dropping them — a picker that silently omits them looks like
the provider stopped offering them. It reads `data[].id` (the OpenAI shape, which
Anthropic and Azure also speak) and falls back to `models[].name` (Ollama's
own); llama.cpp answers with both, so the first is preferred rather than merging
two spellings of one list.

Discovery suggests, it does not decide. Adding a model by hand stays possible
because a listing can be incomplete — a cloud provider need not name every
deployment — and half of a model row is the workspace's policy anyway: what
people call it, its quotas, whether it is on. None of that is discoverable.

A **model** hangs off a provider and carries what a person calls it against what
the API is given — "Claude 3.5 Sonnet" against `claude-3-5-sonnet-20241022` —
plus the workspace's own quotas: a token limit, how often it resets, and a rate.

Usage is a sum over `model_usage_day`, one row per model per day, and every
answered call adds itself: `ModelChatClient` hands the tokens and the latency the
provider reported to `ModelUsageRecorder`, which writes in its own transaction —
a chat that answered should not be rolled back because a counter could not be
written. A model nothing has called reports that its window is empty rather than
a grid of zeros. Cost is worked out from the prices recorded on the model, and is
absent when they are not.

### Agents in a workflow

An **agent node** instances one of the workspace's agents, the same way an action
node instances an action. It stores the id and nothing else: the agent supplies
the model it answers on, the instructions it works under, and the catalogs it was
granted.

It runs through the same loop a chat with an agent uses — same briefing, same
tools, same eight-round limit — and that is deliberate. An agent is one
configuration, and it should behave the same whether somebody is talking to it or
a run is; anything else is two agents under one name, with a difference nobody
sees until it matters. What reached the node becomes the question, and what the
agent says becomes the step's output, so the node after it is handed an answer
the way it would be handed a function's return value.

A node naming no agent is skipped and says so rather than failing the run: a
graph is drawn before it is finished. An agent with no model does fail the step,
because that is a configuration somebody has to fix. And an agent a node
instances cannot be deleted while the node exists — the same rule a condition
follows.

### Sessions

An agent node can be given a **session key**, and everything that happens in its
turn is recorded against it: what it was asked, what it answered, which tools it
called and with what, and a note when something went wrong. Two runs that compute
the same key write into one conversation — a ticket seen by two workflows has one
history rather than two — and a node naming no key records nothing.

What was said before goes back in front of the model, so an agent that keeps a
session remembers it rather than merely writing it down. A session has its own
page under **AI**, searchable and filterable by the kind of line; it can be thrown
away when it should not have been kept; and it can be **continued in chat**, where
somebody picks up the conversation an agent was having and what they say is
written into it too, under their own name rather than the agent's.

### Actions and functions

An **action** is a reusable block a workflow is built from, defined once in the
workspace's catalogue: send something through a connection, send a mail, call an
HTTP endpoint, call one of the workspace's functions, or wait — for a condition,
or for a time. A workflow uses one by pointing an **action node** at it, the same
arrangement as triggers.

What an action needs and what it produces are not stored. They are read off its
settings, so a `{{input.name}}` typed into the content is an input the moment it
is typed, and a function action's output follows the function's return type.

A **function** is JavaScript a workspace wrote, a module whose default export is
called. It runs in GraalJS with the sandbox `ScriptRunner` builds:

- no host classes, no class loading, no `Java`, `Packages` or `Polyglot`
- no files, no network, no threads, no processes, no environment
- no `load`, no `print`, and no timers, so nothing can be pending when a call
  returns
- a statement limit and a wall-clock timeout, either of which stops a script
  that will not finish
- a guard on the heap, because neither of those stops a script that finishes by
  filling it: a call is stopped when the heap is still nearly full after a
  collection and that call is the one that has been allocating, so a script
  cannot take the server down with it. Only so many calls are in a sandbox at
  once, which is what bounds the installation rather than the call
- a fresh context per call, so two runs cannot see each other

Everything crossing the boundary is JSON text; nothing the script touches is a
live Java object. `ScriptRunnerTest` is where those are held.

Every subtype runs. A send goes out through its connection, a mail leaves through
its SMTP server, an HTTP request is made, a function is called, a wait parks. What
is missing is reported rather than invented: a node with nobody to send to, or a
mail with no subject and no body, is a **skipped** step saying why, because a
graph is drawn before it is finished. What was refused is a failure, and mail
tells the two kinds apart — a rejected login or an address the server will not
accept is permanent and stops the run, while a timeout or an unreachable server
is worth another attempt.

What a node **passes** to its action, though, belongs to the node. Selecting an
action node in the editor lists exactly the parameters that action takes, each
with what this node will put in it: `{{input.payload}}` reads what reached the
node, and anything else — `verbose`, `#alerts`, `42` — is passed as written. The
list is seeded from the action the first time the node is saved and is the node's
own from then on, so two nodes can call one function with different arguments and
neither of them can change the definition the other is using. A run copies the
mappings onto its step, which is why an edit made afterwards cannot rewrite what
already happened.

### Waiting

A wait holds nothing while it waits. The node is asked its question — has the
time passed, does the condition hold — and if the answer is not yet it **parks**:
the step is left open, recorded `WAITING`, and it says how long to leave it
before asking again. The delay belongs to whatever is carrying the run.

On Temporal that is `Workflow.sleep`, so a wait costs a timer and no worker. The
activity answers immediately either way, which means `step-timeout-seconds`
bounds the work a node does rather than the time it waits for something, and a
wait survives the restart of every process involved — only `run-timeout-hours`
bounds how long it may be. A wait picked up an hour later counts from when it
first parked, because the deadline is written onto the step.

The inline engine has no timer, so it waits out a parked step on the thread
carrying the run, and a run may only spend `orknux.execution.inline.max-wait` in
total doing so. A workflow that has to wait for an hour works on Temporal and
fails on the inline engine, saying so — which is the honest answer rather than a
pinned thread.

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

Two things are refused when saving. Something feeding a trigger, because a
trigger is where a run starts. And two nodes claiming one output name, because a
run carries what every step produced under the name its node gave it: the later
one would quietly win and every reference to the first would read the wrong
value — a workflow that runs, finishes, and is wrong. Everything else comes
back as advice on the graph — a node needing what nothing produces, a node with
nothing before it, a node with nothing chosen — because a workflow is drawn
before it is finished, and the editor lists them beside the canvas.

**Which way a node faces is the node's own.** `orientation` is
`LEFT_TO_RIGHT`, `TOP_TO_BOTTOM`, `RIGHT_TO_LEFT` or `BOTTOM_TO_TOP`, and it
decides which side the input and output sit on — named for where the work goes,
because that is what somebody means. Null is the way it always was, so every node
drawn before this keeps its shape. It is per node rather than per workflow: a
chain that runs across the top and then turns down the side is what somebody
draws when they have room, and one direction for the whole graph would trade one
constraint for another.

### Conditions

A condition is a question a workspace asks about what a run is carrying, defined once
and used from three places: a wait that holds until it holds, an action that
waits on it, and a **condition node**.

A condition node has two ways out. Draw an edge on the Yes side and an edge on
the No side and the run follows the one the answer chose, so an alternative path
is a branch rather than a second workflow; the two ways out can be labelled, and
"Escalate" and "File it" are what make a graph readable at a glance. `BranchGate`
is what does it — an edge with no branch is followed whatever the answer, which
is every edge drawn before branches existed.

**A condition nobody has branched stops the run instead**, which is what one
always did: where every edge leaving it is a plain one, a No ends the run rather
than deciding a direction. Stopping is not failing — the workflow asked and acted
on the answer — so the run
finishes completed, with where and why recorded on the run itself
(`stopped_at_node_key`, `stopped_reason`). Otherwise it would be indistinguishable
from a run that did everything: the executions list marks it **Stopped early**,
the run detail says which node ended it and what it said, and the steps after it
read "Not reached" rather than "Pending".

A condition is data rather than code: a type (Slack, Jira, Time), a property, a
check, and what to check against, with a Negate switch that turns the answer
round — which is what makes "Is External User" the same definition as "Is
Workspacemate Message" negated. Two kinds are made of other things: **Any Of** and
**All Of** combine conditions, and **Function** calls one of the workspace's
functions, which has to return a boolean.

What a condition means in words is not stored. The list's description and the
sentence under the builder are read off the definition, so they cannot drift
from what will actually be asked.

### When a step fails

A failed step used to end the run, and that was the only answer there was. An
action or an agent node now carries a **retry policy** — how many attempts, and
how long to wait between them, fixed or doubling — and can be given a second way
out, drawn as a red **If fails** edge beside the green one. The failure is still
recorded on the step; what changes is that the run can carry on down the other
line instead of stopping. One attempt is no policy rather than a policy of one,
so a node left at the default costs nothing.

A failure already known to be final never spends an attempt. A thrown function
throws the same way next time and so does a provider that refused the request it
was sent, so only what might come out differently — a timeout, a 429, a 5xx, a
dropped connection — is asked again. The wait between attempts is taken the way a
wait node's is, which is what keeps the policy from being applied twice: the step
parks, so Temporal has no failure of its own to retry on top of it, and a doubling
backoff runs down on a durable timer rather than inside a step holding a worker.

Retries on an agent are the ones to think about before turning them on: **every
attempt is another billed call**, and nothing caps that.

### Publishing

**Publishing takes a copy, and the copy is what runs.** Until it did, Publish set
a word on a screen and nothing read it: a trigger fired every workflow that had a
node instancing it and the runner read the rows as they stood, so an event
arriving while somebody was halfway through drawing ran the half-drawn graph. The
badge said Draft and the graph ran anyway, which was the honest answer to "what
is the difference between Save and Publish" — there was none.

So the editable rows are the **draft**, and `publishWorkflow` writes a snapshot
of the runnable graph into `workflow_publication` — one row per publication, and
the newest of them is what runs. A trigger, a schedule or the API runs that
snapshot; a person pressing **Run** gets the draft, because they mean the graph
on their screen. Which one is used is read off what started the run —
`ExecutionPlanner` asks for `GraphVersion.DRAFT` only
for `MANUAL` — rather than from a setting somebody has to remember. Editing and
saving change the draft alone, which is what makes it safe to leave a graph
half-finished overnight, and a graph edited after publishing is not the graph an
event runs until somebody publishes again. **The graph**, and not everything the
graph calls — which is the next paragraph, and the half people are surprised by.
Saving does put the status back to `DRAFT`, which is the badge somebody reads —
the snapshot goes on being what runs, so the badge is saying "what you are
looking at is not what is live", which is exactly the thing worth knowing.

**What the copy holds is the graph, and of the things it calls only their ids.**
A node carries an `actionId`, an `agentId`, a `conditionId`, and each node runner
resolves its id against the live row at the moment the step runs. So publishing
freezes the shape of a workflow and not its behaviour: edit a function a
published workflow calls and that workflow answers differently on its next run,
with nobody having republished anything and no badge anywhere turning back to
Draft. Most of the time that is the point — a fix to a shared function reaching
every caller at once is most of the reason functions are shared — but it is not
what the word "published" promises, so it is worth saying out loud.
`PublishedDefinitionsTest` keeps the sentence true.

**A workflow's publications are its versions, and they are kept.** The table held
one row per workflow, overwritten on every Publish, which made "what did this run
last month" unanswerable. Each publish now writes another row, the workflow's
settings page lists them, and an older one is put back into service by publishing
it again — `restored_from` says which one was copied, so a rollback is a thing
that happened rather than the deletion of everything after it. The draft on the
canvas is untouched by a restore: a workflow's versions are its publications and
not its saves, so nothing half-finished is lost, and the badge says Draft while
the two differ. Variables are deliberately not versioned — their values are
encrypted, and old versions would be old secrets.

A workflow nobody has published has nothing to run and says so in its own
exception, `WorkflowNotPublishedException`, rather than as "not found": one is a
wrong id and the other is a graph that exists and is not ready, and somebody
whose trigger did nothing needs to know which. The one case that is not refused
is a workflow that was already marked published when this arrived — it has no
snapshot, and refusing it would take a working installation down on upgrade, so
its first run takes the copy publishing would have taken, which is what was
running a minute earlier.

The snapshot holds the graph as the execution module reads it — nodes, their
bindings, the edges and their branches — rather than a second set of tables
shadowing the first, and it is written and read by hand rather than by
reflection: a shape stored in a database outlives the class it came from.

### Triggers

The Triggers screen is a workspace's **catalogue**: each entry describes an event —
one arriving on a connection, a cron expression, or a URL this installation
answers on — and names no workflow. A
workflow picks one up in the editor, by pointing a trigger node at a definition;
that node is the **instance**, and it is what wires the event to that workflow.
One definition can be instanced by several workflows, and an entry nobody
instances starts nothing.

A trigger can also carry a **payload**: a JSON object handed to the runs it
starts. The clock carries no data, so without one a scheduled workflow is handed
nothing but the cron expression and a function called from it has nothing to
work on. An incoming trigger puts its payload underneath what arrived, so the
event wins where both name a field.

All three run. An incoming one fires when its event arrives; a scheduled one
fires on its cron expression, in its timezone, from a db-scheduler task that
ticks once a minute — which is what makes a schedule survive a restart, fire
once however many instances are up, and be unable to mean anything smaller than a
minute. The workflows list shows both ends of that:
when each workflow last ran, and when the clock will start it next.

A **webhook** trigger answers at `/api/webhooks/<path>`, which is how something
with no connection and no clock starts a workflow — a build finishing, a form
being submitted, another product's own webhook. Its caller is the open internet,
so a path nothing listens on, a trigger that has been turned off and a body that
is not the shape the trigger promises all answer 404 alike: telling them apart
would tell whoever is probing that something is there and what it expects. A
caller who fails to prove who they are gets 401 instead, and that is written into
the trigger's history — a webhook whose caller has the wrong secret otherwise
looks exactly like a webhook nobody is calling. Proving it is either nothing at
all or one of the workspace's functions, handed the request and believed, which
is how a signature is checked against a secret nobody has to paste into a graph.

Slack arrives over **Socket Mode**, a websocket orknux dials out on, so a
self-hosted installation needs no public URL and no inbound rule. Add a
connection of type **Slack** — a bot token (`xoxb-…`) to call the API with, and
optionally an app-level token (`xapp-…`) — and it is that second credential
rather than the type that decides whether this installation listens as well as
sends: `SlackListener` opens a socket for every Slack connection holding one,
within `orknux.slack.reconcile-seconds`;
an `app_mention` then matches every enabled definition watching that connection
for a mention, and runs each workflow instancing one, with the message, channel
and thread handed to the run. Set
`orknux.slack.enabled: false` to open no sockets at all.

## Licence

**GNU Affero General Public License v3.0 or later** — see [LICENSE](LICENSE),
and [NOTICE](NOTICE) for the section 7(b) term requiring the attribution shown in
the interface to be preserved.

You may run this, modify it, host it and charge for it. If you let people use a
modified version over a network, section 13 requires you to offer them that
version's source under the same licence.

A commercial licence, which lifts both the attribution term and the source
obligations, is available from the copyright holder.

Copyright (C) 2026 Michał Szymański.
