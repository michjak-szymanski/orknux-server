# orknux-server

[![CI](https://github.com/michjak-szymanski/orknux-server/actions/workflows/ci.yml/badge.svg)](https://github.com/michjak-szymanski/orknux-server/actions/workflows/ci.yml)
[![Tests](https://img.shields.io/github/actions/workflow/status/michjak-szymanski/orknux-server/ci.yml?branch=main&label=tests)](https://github.com/michjak-szymanski/orknux-server/actions/workflows/ci.yml)
[![Licence](https://img.shields.io/github/license/michjak-szymanski/orknux-server?label=licence)](LICENSE)

Orknux — pronounced *ZAV-rick* — is fully open source, workspace based, agent
orchestration platform.

A Kotlin/Spring Boot GraphQL API over Postgres, with sign-in against LDAP. One
deployable, built from modules that cannot reach into each other:

```
orknux-ui ──▶ app ──┬──▶ connection ──▶ Slack, Jira, GitHub, Teams
                    └──▶ execution  ──▶ Temporal
```

| module               | owns                                                         |
|----------------------|--------------------------------------------------------------|
| `app`                | Workspaces, workflow definitions, agents, sign-in, the audit log, and the GraphQL API the browser talks to |
| `modules/connection` | Connections, MCP servers and every credential                 |
| `modules/execution`  | Runs: the engine, the Temporal worker, and what each run did  |

The modules are separate Maven artifacts, so the compiler enforces the boundary:
neither may depend on `app`. Where one needs something the app owns, it declares
an interface and the app implements it — `WorkspaceDirectory` for the workspaces a backfill
reaches, `WorkflowGraphSource` for the graph a run is given. That is also the
seam to pull on if one of them ever has to become its own service.

[orknux-ui](https://github.com/michjak-szymanski/orknux-ui) is the React front
end, and talks only to this service.

## Running

```
docker compose up -d              # postgres, openldap and temporal
./mvnw spring-boot:run -pl app    # http://localhost:8080
```

The first build has to be online, and `-pl app` builds the modules it needs.
Temporal's own UI is on http://localhost:8233, for looking at a run that went
wrong.

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

Flyway migrates the schema on start; JPA runs with `ddl-auto: validate`, so the
migrations are the only thing that changes the database. One process means one
database and one migration history, in `app/src/main/resources/db/migration`.

Sign in with a directory user from `docker/ldap/bootstrap.ldif`: 

| user    | password   | groups                          |
|---------|------------|---------------------------------|
| `alice` | `password` | `admins`, `users`               |
| `bob`   | `password` | `users`, `backend`              |

These are development fixtures. The LDAP admin is `cn=admin,dc=orknux,dc=io` /
`admin`, and Postgres is `orknux` / `orknux`.

## Tests

```
./mvnw test                            # every module 
./mvnw test -Dtest=IntegrationAPITest  # one class
```

The tests are `@SpringBootTest` against **their own Postgres**, started as a
container for the run and thrown away with it, so a suite cannot touch the
database you are developing against. The fixtures clear the tables they use, and
`deleteAll()` cannot tell whose rows are whose — pointed at the development
database it takes the workspaces, models and chat history you were looking at.
`TestDatabase` starts the container before any Spring context exists; Docker has
to be running, LDAP still comes from compose.

They run with `orknux.temporal.enabled=false`, so a workflow runs on the calling
thread and no Temporal server is needed; the Temporal path has its own test,
which brings up an in-process environment. `orknux.model.check.enabled=false`
keeps the provider sweep from calling anything while a suite runs.

## How it is put together

| package in `app` | what lives there                                                      |
|------------------|------------------------------------------------------------------------|
| `security`       | Session endpoint, workspace visibility, the configurable admin role         |
| `ldap`           | Bind authentication and the group-to-authority mapping                 |
| `workspace`           | Workspaces and the audit log every other package writes to                  |
| `workflow`       | Workflow definitions, the editable graph, and the API over runs        |
| `agent`          | Agents, the MCP servers they may use, the tools they may call and the skills that guide them |
| `integration`    | The integration API over the connection module                         |
| `trigger`        | The trigger catalogue, the listener, and the clock that fires the scheduled ones |
| `action`         | The action catalogue, the workspace's JavaScript functions, and the runtime for an action node |
| `condition`      | The condition catalogue, what decides one, and the condition node |
| `chat`           | Chats, and the Spring AI conversation each one is |
| `model`          | The API over the workspace's LLM providers, the models reached through them, and what they were used for |
| `monitoring`     | The health of the service and everything it needs to be up             |

The GraphQL schema is `app/src/main/resources/graphql/schema.graphqls`;
controllers are `@Controller` classes with `@QueryMapping` / `@MutationMapping`.

### Access

`orknux.security.admin-role` (default `ROLE_ADMINS`) names the role that sees the
Admin section and every workspace. Everyone else sees a workspace only if they
belong to the directory group named on it: `cn=backend,ou=groups,…` grants
`ROLE_BACKEND`, and `WorkspaceAccess` checks that on every read and write. A workspace with
no group is administrators-only. Group lookup needs
`orknux.ldap.group-search-base` to point at the OU holding those groups.

### Audit

`WorkspaceAuditRecorder` writes one row per change, attributed to the LDAP uid of the
caller. Entries carry a category (`WORKSPACE`, `WORKFLOW`, `AGENT`, `INTEGRATION`, `MODEL`) and
a message ready to display. An entry with no workspace is an admin-level change
and only appears in the admin audit log.

### Where the modules meet

A controller in `app` checks access, calls the module, and records the audit
entry — in that order. The modules hold no notion of a user and never check one:
they cannot, and the check belongs where the directory groups are.

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

Secrets are stored as plain columns. They want envelope encryption or an external
secret store before this runs anywhere but a development machine.

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
calls the endpoint with the header that type wants — `x-api-key`, `api-key`,
`x-goog-api-key`, or a bearer — and the Entra path performs the client
credentials grant, which is the only thing that says whether the tenant, client
and secret go together. Anything that could change what a check would find
forgets the last one, so a stored answer never describes a provider that has
moved.

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

Usage is a sum over `model_usage_day`, one row per model per day. **Nothing
writes to it yet**, because no runtime calls a model, so a model reports that its
window is empty rather than a grid of zeros — the same rule an action with no
runtime follows. Cost is worked out from the prices recorded on the model, and is
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

### Actions and functions

An **action** is a reusable block a workflow is built from, defined once in the
workspace's catalogue: send something through a connection, call an HTTP endpoint,
call one of the workspace's functions, or wait — for a condition, or for a time. A
workflow uses one by pointing an **action node** at it, the same arrangement as
triggers.

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
- a fresh context per call, so two runs cannot see each other

Everything crossing the boundary is JSON text; nothing the script touches is a
live Java object. `ScriptRunnerTest` is where those are held.

Only the function and wait actions have a runtime today. An outgoing connection
or an HTTP request records that it was not performed rather than claiming it
was.

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

Two shapes are refused when saving, because they could never run: something
feeding a trigger, and something following a publish task. Everything else comes
back as advice on the graph — a node needing what nothing produces, a node with
nothing before it, a node with nothing chosen — because a workflow is drawn
before it is finished, and the editor lists them beside the canvas.

### Conditions

A condition is a question a workspace asks about what a run is carrying, defined once
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
Workspacemate Message" negated. Two kinds are made of other things: **Any Of** and
**All Of** combine conditions, and **Function** calls one of the workspace's
functions, which has to return a boolean.

What a condition means in words is not stored. The list's description and the
sentence under the builder are read off the definition, so they cannot drift
from what will actually be asked.

### Triggers

The Triggers screen is a workspace's **catalogue**: each entry describes an event —
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

Slack arrives over **Socket Mode**, a websocket orknux dials out on, so a
self-hosted installation needs no public URL and no inbound rule. Add a
connection of type **Slack (Socket Mode)** — its form asks for the two
credentials the listener uses, a bot token (`xoxb-…`) and an app-level token
(`xapp-…`) — and `SlackListener` opens a socket for it within
`orknux.slack.reconcile-seconds`;
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
