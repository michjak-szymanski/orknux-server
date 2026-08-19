# orknux-server

Workspace-based agent orchestration: workflows drawn as a graph, run durably,
with the agents, models, connections and credentials a workspace holds, and an
issue tracker beside them that an assistant can work through over MCP.

This image is the API and the engine. The interface people sign in to is
[`orknux/orknux-ui`](https://hub.docker.com/r/orknux/orknux-ui), which talks
only to this.

- **Source:** https://github.com/michjak-szymanski/orknux-server
- **Licence:** AGPL-3.0-or-later
- **Exposes:** `8080`
- **Runs as:** `orknux`, not root
- **Tags:** `latest` follows `main`; `X.Y.Z` and `X.Y` come from release tags;
  `sha-<commit>` never moves.

## What it needs

A database - Postgres, or SQLite and no second container - something to sign in
against (a directory, or an OIDC provider), and Temporal. With the default
configuration the application **refuses to start** when Temporal is not reachable
— deliberately, so a deployment brought up before its Temporal restarts until
that service answers rather than accepting work it cannot run.

```yaml
services:
  orknux-server:
    image: orknux/orknux-server:latest
    ports: ["8080:8080"]
    environment:
      ORKNUX_SECRET_KEY: "a-32-byte-key-you-generated"     # see below
      ORKNUX_DB_URL: jdbc:postgresql://postgres:5432/orknux
      ORKNUX_DB_USERNAME: orknux
      ORKNUX_DB_PASSWORD: orknux
      ORKNUX_LDAP_URLS: ldap://ldap:389
      ORKNUX_TEMPORAL_TARGET: temporal:7233
      ORKNUX_ALLOWED_ORIGINS: https://orknux.example.com
      ORKNUX_ATTACHMENTS_LOCATION: /home/orknux/attachments
    volumes:
      - orknux-data:/home/orknux   # only if attachments are on
```

The volume goes on the server user's home directory rather than somewhere
tidier like `/app/data`, and that is not a style choice. This image runs as
`orknux`, not root; a named volume mounted on a path the image does not already
contain is created owned by root, and the server then cannot write a single
attachment. `/home/orknux` exists in the image and belongs to that user, so the
volume inherits the ownership. Move it if you like, but move it somewhere that
user can write.

A whole installation, with the database, the directory and Temporal alongside
this, is [`deploy/compose.yaml`](https://github.com/michjak-szymanski/orknux-server/blob/main/deploy/compose.yaml)
in the source repository.

## Reading the tables

Every setting is one environment variable, all prefixed the same way, so
`env | grep ORKNUX_` is the whole of an installation's configuration. Each is
listed with what it does, what happens if you say nothing, and whether you have
to say anything at all.

**Required** means: will this installation be wrong without it?

- **Yes** — set it, or the thing it configures does not work.
- **No** — the default is a real answer; change it when you want something else.
- **Conditional** — required only in the case named, and ignored otherwise.

The defaults are development defaults. They make the server run on a laptop
against a local Postgres, LDAP and Temporal; several of them are the wrong
answer in a deployment, and those say so.

## The one that matters most

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_SECRET_KEY` | Encrypts every credential this server is trusted with — provider keys, Slack tokens, MCP secrets — so the database on its own is not enough to use them. 32 bytes, base64. | *none* | **Yes** |

There is deliberately **no default**: a key committed to an image would be a key
every installation shares, which is the same as no key at all. Generate one and
keep it — `openssl rand -base64 32`. **Changing or losing it makes every stored
credential unreadable**; nothing decrypts them afterwards, and they have to be
entered again. The Doctor page under Admin tells you whether the key is set, the
right length, and whether every stored secret can be read with it.

## Database

Postgres or SQLite. Which one is decided by `ORKNUX_DB_URL` and nothing else —
the driver, the dialect and the migrations all follow from it.

Postgres is what a deployment should use: it takes more than one writer, and it
is what this is tested against by default. SQLite is a single file with nothing
else to run, for an installation of one or a few. It means one writer at a time,
one machine, no time zone kept on a timestamp, and a backup that is a file copy
taken while nothing is writing. The README's **The database** section is the full
list.

Under SQLite the username and password are ignored. Give the file a path on a
volume that outlives the container, and make sure the directory exists — the
server creates the file, not the directory it sits in, and says so by name if it
is missing.

```
ORKNUX_DB_URL: jdbc:sqlite:/data/orknux.db
```

The schema is Flyway's either way, and JPA runs with `ddl-auto: validate`, so the
migrations are the only thing that ever changes it.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_DB_URL` | JDBC URL of the database, and what picks which database. `jdbc:postgresql://host:5432/orknux` or `jdbc:sqlite:/data/orknux.db`. | `jdbc:postgresql://localhost:5432/orknux` | **Yes** in a deployment — the default points at localhost |
| `ORKNUX_DB_USERNAME` | The user it connects as. Ignored under SQLite. | `orknux` | **Yes** in a Postgres deployment |
| `ORKNUX_DB_PASSWORD` | That user's password. Ignored under SQLite. | `orknux` | **Yes** in a Postgres deployment |
| `ORKNUX_DB_MIGRATE` | Whether Flyway migrates on start. Turn off only where something else owns the schema; the application expects it to be at the version this build ships. | `true` | No |

## Signing in

`ORKNUX_AUTH_METHOD` picks one, and only one. Both at once would mean an LDAP
password for every account the OIDC provider governs — a second way in that its
policies do not reach and its administrators do not know about.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_AUTH_METHOD` | `LDAP` or `OIDC`. | `LDAP` | No |
| `ORKNUX_ADMIN_ROLE` | The role that sees the Admin section and every workspace. | `ROLE_ADMINS` | No |

**How hard somebody may try.** Sign-in is the one door anybody may knock on, and
a username somebody knows exists could otherwise be tried at whatever rate the
network allowed - which under LDAP landed on the directory as well. A wrong
password costs nothing until the allowance is spent, then a pause that doubles up
to the ceiling and stops there.

Nothing here locks anybody out, deliberately: the pause has an end, a successful
sign-in clears the record, and so does going quiet for the forget time. An
account that locks is an account a stranger can close by guessing at it badly on
purpose. Both counts are kept at once, because per username alone does not slow a
list of names and per address alone does not slow a botnet. The state is in
memory in this process, so a restart forgets it and two replicas keep a count
each.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_SIGN_IN_PER_USERNAME` | Tries against one username that cost nothing; the next one waits. | `5` | No |
| `ORKNUX_SIGN_IN_PER_ADDRESS` | Tries from one address that cost nothing. Higher, since an address is not a person: an office behind one router is one address. | `20` | No |
| `ORKNUX_SIGN_IN_FIRST_WAIT` | The pause on the first failure past the allowance. It doubles after that. | `2s` | No |
| `ORKNUX_SIGN_IN_LONGEST_WAIT` | Where the doubling stops. | `5m` | No |
| `ORKNUX_SIGN_IN_FORGET_AFTER` | How long a quiet username or address is remembered for, so a bad afternoon does not follow anybody into the next day. | `15m` | No |

**LDAP** — read only when `ORKNUX_AUTH_METHOD=LDAP`.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_LDAP_URLS` | The directory to bind to. | `ldap://localhost:389` | **Yes** under LDAP |
| `ORKNUX_LDAP_BASE` | The root of the tree everything below is relative to. | `dc=orknux,dc=io` | **Yes** under LDAP |
| `ORKNUX_LDAP_BIND_DN` | The account this server binds as to search. | `cn=admin,dc=orknux,dc=io` | **Yes** under LDAP |
| `ORKNUX_LDAP_BIND_PASSWORD` | That account's password. | `admin` | **Yes** under LDAP |
| `ORKNUX_LDAP_USER_SEARCH_BASE` | Where people are looked for, under the base. | `ou=people` | No |
| `ORKNUX_LDAP_USER_SEARCH_FILTER` | How a typed username is matched; `{0}` is what was typed. | `(uid={0})` | No |
| `ORKNUX_LDAP_GROUP_SEARCH_BASE` | Where groups live. Workspace groups have to be under this base for membership to be picked up; empty disables group search entirely. | `ou=groups` | No |
| `ORKNUX_LDAP_GROUP_SEARCH_FILTER` | How a person's groups are found; `{0}` is their DN. | `(member={0})` | No |

**OIDC** — read only when `ORKNUX_AUTH_METHOD=OIDC`, and ignored otherwise. Two
flows at once: a browser is sent to the provider and comes back with a code,
exchanged for the same session cookie a password sign-in issues; a script
presents the provider's token as a bearer instead, validated per request.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_OIDC_ISSUER` | The provider. Its discovery document says where the endpoints are and which keys sign its tokens, so none of that is written here to go stale. | *none* | **Yes** under OIDC |
| `ORKNUX_OIDC_CLIENT_ID` | This installation, as the provider knows it. | *none* | **Yes** under OIDC |
| `ORKNUX_OIDC_CLIENT_SECRET` | Its secret. | *none* | **Yes** under OIDC |
| `ORKNUX_OIDC_SCOPES` | What is asked for, comma separated. | `openid,profile,email,groups` | No |
| `ORKNUX_OIDC_USERNAME_CLAIM` | The claim to show as somebody's name. The subject is the fallback, being stable and unreadable. | `preferred_username` | No |
| `ORKNUX_OIDC_ROLES_CLAIM` | The claim carrying group or role membership; there is no standard one. Keycloak and Okta usually say `groups`, Entra says `groups` or `roles`. Each value in it is treated the way an LDAP group is. | `groups` | No |
| `ORKNUX_OIDC_DISPLAY_NAME` | What the sign-in button says, in the words the people signing in use. | `single sign-on` | No |
| `ORKNUX_OIDC_AUDIENCES` | Which audiences a bearer token may name, comma separated. Empty means the client id. **Read the paragraph below before you upgrade an OIDC installation** - the wrong answer here is a 401 on every API call. | *the client id* | **Conditional** - see below |

**`ORKNUX_OIDC_AUDIENCES` is the one that can lock people out on upgrade.** A
bearer token has to name this installation in its `aud` claim, and empty means
the client id, which is what a provider writes into a token minted for this
application. Two common providers write something else:

- **Keycloak** names `account`, unless an audience mapper is configured against
  this client.
- **Entra** names the application's **App ID URI** - `api://…` - rather than its
  client id.

Neither is the client id. The `aud` claim has been checked since **0.5.0** -
before that only the issuer was, so any token the provider minted was accepted,
including one issued to a different application in the same Keycloak realm or
Entra tenant. An installation coming from 0.4 or earlier therefore accepts those
tokens today and refuses them after the upgrade: a 401 on API calls that worked
yesterday, with `The aud claim is not valid` in the server log.
Browser sign-in is unaffected either way - this checks bearer tokens, and the
code flow is a different door.

There are two ways out and you only need one: configure the provider to name
this client, or set `ORKNUX_OIDC_AUDIENCES` to what its tokens actually carry.
It takes a list, and a token has to match **one** of them rather than all -
`ORKNUX_OIDC_AUDIENCES=account,api://orknux` accepts either.

Which of the provider's names grants which of this installation's roles is
`orknux.security.role-mapping`, and it is **YAML only** — the keys are group DNs
and claim values, full of dots, equals signs and commas, and the
environment-variable spelling of one is not something anybody should have to
work out. Empty is a working configuration: a role with no mapping is granted to
whoever holds an authority derived from its own name.

**The first administrator**, for an installation running with neither a
directory nor an OIDC provider. Nothing else can make one: an account is created
by an administrator, or written down when a provider vouches for somebody at the
door, so an installation with no provider has nobody to create the administrator
who could create you. Set both of these and one internal administrator is
created at startup, holding the built-in `Administrators` role and signing in
with a password on the sign-in form, which internal users may always do whatever
`ORKNUX_AUTH_METHOD` says.

It only ever creates. A user of that name that already exists is left exactly as
it is, password and roles alike, so leaving the variables set cannot put back a
password somebody changed or a role somebody deliberately took away, and the log
says it left the account alone. The password has to be at least 12 characters,
the same minimum as everywhere else; a shorter one seeds nobody rather than
making an account nobody could use.

A password in an environment variable is readable by anything that can see the
process and sits in whatever `.env` file it was written into. It is a way in
rather than a credential to keep: sign in, change it from the account's own
preferences, then unset both variables.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_BOOTSTRAP_ADMIN_USERNAME` | The first administrator's username, created at startup if no user has it. Empty seeds nobody. | *none* | **Yes** with no directory and no OIDC |
| `ORKNUX_BOOTSTRAP_ADMIN_PASSWORD` | What they sign in with the first time. At least 12 characters. Change it from inside and unset this. | *none* | With the above |

**Resetting a forgotten password** — a link mailed to the address on the
account, good once and for an hour. Only for a user this installation made up
who already has a password: a directory or OIDC account's password belongs to
the provider. Off until the mail server below and `ORKNUX_BASE_URL` are set, and
until they are the form still answers the same polite sentence and the log says
what is missing.

The mail server is the installation's own, deliberately not a workspace's SMTP
connection: that credential belongs to one team, would stop working the day they
rotated it, and has nothing to do with an account that may belong to no
workspace at all.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_MAIL_HOST` | The relay this server sends its own mail through. Empty means it cannot, and cannot offer a password reset. | *none* | **Yes** for password resets |
| `ORKNUX_MAIL_FROM` | What the mail is from. A relay will not take a message without one. | *none* | **Yes** for password resets |
| `ORKNUX_MAIL_PORT` | Empty takes what the security below usually listens on: 587, 465 or 25. | *by security* | No |
| `ORKNUX_MAIL_USERNAME` | Empty sends without authenticating, which is what an internal relay usually wants. | *none* | No |
| `ORKNUX_MAIL_PASSWORD` | That account's password. | *none* | No |
| `ORKNUX_MAIL_SECURITY` | `NONE`, `STARTTLS` or `TLS`. STARTTLS is required rather than merely offered, so a server that has stopped offering it is refused rather than quietly taking the password in the clear. | `STARTTLS` | No |
| `ORKNUX_PASSWORD_RESET_EXPIRY` | How long a mailed link works for. The link is a secret sitting in a mailbox, so what matters is not how long a person needs but how long that copy stays dangerous. | `1h` | No |
| `ORKNUX_PASSWORD_RESET_PER_EMAIL` | Requests about one address that cost nothing; the next one waits. | `3` | No |
| `ORKNUX_PASSWORD_RESET_PER_ADDRESS` | Requests from one caller that cost nothing. Higher, since an office behind one router is one address. | `20` | No |
| `ORKNUX_PASSWORD_RESET_FIRST_WAIT` | The pause on the first request past the allowance; it doubles after that. | `2s` | No |
| `ORKNUX_PASSWORD_RESET_LONGEST_WAIT` | Where that doubling stops. | `5m` | No |
| `ORKNUX_PASSWORD_RESET_FORGET_AFTER` | How long a quiet address is remembered for. | `15m` | No |

The forgotten-password form is throttled by the same rules as sign-in and counted
separately, so somebody asking about your account repeatedly cannot put you into
a pause on the sign-in page.

## Runs

Temporal is what makes a run durable — it survives a restart, retries a step,
and can be looked at afterwards.

What a trigger, a schedule or the API runs is the workflow **as it was
published**, not as it is being edited. Publishing takes a copy; saving the
editor changes the draft, and Run in the editor is the one thing that uses it. A
workflow nobody has published has nothing to run and says so. Nothing to do on
upgrade: a workflow that was already marked published takes that copy on its
first run, which is the graph that was running a minute earlier.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_TEMPORAL_ENABLED` | `false` runs a workflow on the calling thread, with no retries and no resumption — for a single-process installation with no Temporal. The tests do that; a deployment should not. | `true` | No |
| `ORKNUX_TEMPORAL_TARGET` | Host and port of the Temporal frontend. | `localhost:7233` | **Yes** in a deployment, unless Temporal is off |
| `ORKNUX_TEMPORAL_NAMESPACE` | The Temporal namespace to run in. | `default` | No |
| `ORKNUX_TEMPORAL_TASK_QUEUE` | The queue workers take work from. Change it to run two installations against one Temporal. | `orknux-workflow` | No |
| `ORKNUX_TEMPORAL_RUN_TIMEOUT_HOURS` | How long a whole run may take, waits included. | `24` | No |
| `ORKNUX_TEMPORAL_STEP_TIMEOUT_SECONDS` | How long one step's own work may take. A model call is slow, so a step is given minutes. This does not bound what a step *waits* for: a wait parks the step and answers. | `300` | No |
| `ORKNUX_TEMPORAL_STEP_ATTEMPTS` | How many times a failing step is tried, since most of what a step does is call something else. | `3` | No |
| `ORKNUX_TEMPORAL_UI_URL` | Temporal's own web interface, used only to link out to it from a run. Empty offers no links, which is right where it is not exposed. | `http://localhost:8233` | No |
| `ORKNUX_INLINE_MAX_WAIT` | Only the inline engine: how long a run may spend parked in total before the step fails and says what would have carried it. A Temporal wait is a timer and is bounded by the run timeout instead. | `5m` | No |
| `ORKNUX_SCHEDULER_ENABLED` | The clock behind scheduled triggers. Its state is in the database, so one instance fires a schedule however many are running. | `true` | No |
| `ORKNUX_SCHEDULER_POLLING_INTERVAL` | How often it looks for due work. | `10s` | No |
| `ORKNUX_SCHEDULER_THREADS` | How many due schedules it may start at once. | `4` | No |

## What a workspace's code may do

A workspace's JavaScript runs in a GraalJS sandbox with no host access, no
files, no network and no threads. These bound what it can spend.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_SCRIPT_TIMEOUT_MILLIS` | A function or tool that runs longer is stopped. | `5000` | No |
| `ORKNUX_SCRIPT_STATEMENT_LIMIT` | How many statements one may execute — what catches a loop that never ends. | `5000000` | No |
| `ORKNUX_HTTP_REQUEST_TIMEOUT_SECONDS` | How long a workflow's own HTTP request may take. A step that never returns holds the run that made it. | `30` | No |

## Models and connections

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_MODEL_TIMEOUT` | How long a model has to answer. Generous: a large local model on a laptop is slow, and giving up on it is worse than waiting. | `2m` | No |
| `ORKNUX_MODEL_CHECK_ENABLED` | Periodically asks each provider whether it still answers, so a status on the screen was true recently rather than whenever somebody last pressed the button. | `true` | No |
| `ORKNUX_MODEL_CHECK_INTERVAL` | How often that sweep runs. | `5m` | No |
| `ORKNUX_MODEL_CHECK_INITIAL_DELAY` | How long after start the first sweep waits. | `30s` | No |
| `ORKNUX_CONNECTION_CHECK_ENABLED` | The same, for connections. | `true` | No |
| `ORKNUX_CONNECTION_CHECK_INTERVAL` | How often connections are checked. | `5m` | No |
| `ORKNUX_CONNECTION_CHECK_INITIAL_DELAY` | How long the first check waits. | `30s` | No |
| `ORKNUX_CONNECTION_PROBE_TIMEOUT_SECONDS` | How long a check may take to find out whether anything is listening. | `5` | No |
| `ORKNUX_CONNECTION_ALLOW_LINK_LOCAL` | Link-local addresses reach cloud instance metadata, so they are refused. Turning this on lets a workspace's connection reach them. Private and loopback addresses stay reachable either way, since internal services are the point. | `false` | No |
| `ORKNUX_SLACK_ENABLED` | Opens one Socket Mode websocket per Slack connection holding an app-level token, and turns arriving mentions into workflow runs. | `true` | No |
| `ORKNUX_SLACK_RECONCILE_SECONDS` | How often open sockets are compared with stored connections, so a token pasted into the settings form starts listening without a restart. | `30` | No |
| `ORKNUX_SLACK_RETRY_FAILED_SECONDS` | How long a connection Slack refused is left alone. Changing the token clears the wait, so a corrected credential is not held back by it. | `300` | No |

**A workspace's mail is not configured here.** The `ORKNUX_MAIL_*` variables
above are the installation's own relay, and the only thing it sends today is a
password reset. Mail a *workflow* sends is a connection like any other: the host,
the port, the login, the address to send from and how the session is secured are
typed into a workspace's connection form, and the password goes through the same
encryption every other credential does. The two are deliberately separate - a
workspace's credential belongs to one team and would stop working the day they
rotated it, and a password reset has to work for somebody who belongs to no
workspace at all.

## Chat, attachments and the tracker

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_CHAT_ENABLED` | Whether this installation has a chat at all. `false` is final: an administrator can turn the chat off from the screen, but not back on where the operator has said no. | `true` | No |
| `ORKNUX_ATTACHMENTS_ENABLED` | Whether files may be attached at all — to a chat message, to an issue, or to a comment on one. `false` is final in the same way, and it hides the upload controls and refuses the endpoint without hiding files already uploaded: switching uploads off is not the same as removing evidence. | `true` | No |
| `ORKNUX_ATTACHMENTS_LOCATION` | Where the bytes go, one directory per workspace, whatever they were attached to. **Relative resolves against the working directory**, which is right on a laptop and wrong in a container: give an absolute path on a volume, or attachments land in a layer that goes when the container does. | `data/attachments` | **Yes** if attachments are on |
| `ORKNUX_ATTACHMENTS_MAX_FILE_SIZE_MB` | The largest file that will be accepted, refused with a sentence rather than a stack trace. | `25` | No |
| `ORKNUX_UPLOAD_MAX_FILE_SIZE` | The servlet's own cap on one uploaded file. Keep it at or above the attachment cap, or the larger limit is never reached. | `25MB` | No |
| `ORKNUX_UPLOAD_MAX_REQUEST_SIZE` | The cap on a whole upload request — a file plus what comes with it. | `26MB` | No |

One switch and one directory for both, deliberately: the tracker's attachments
are the chat's attachments, in the same store, under the same limit and the same
rule about which pictures may be shown inline rather than downloaded. The
workspace's own issue tracker needs nothing else configured here.

## Sessions, HTTP and logging

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_PORT` | The port this server listens on inside the container. | `8080` | No |
| `ORKNUX_ALLOWED_ORIGINS` | Where the interface is served from, when it is not this server. Comma separated; empty allows none, which is right once they share an origin. | `http://localhost:5173` | **Yes** where the interface is on another origin |
| `ORKNUX_BASE_URL` | Where the interface is reached from, as somebody's browser spells it. It is what a mailed password reset link points at. Configured rather than worked out from the request, because the `Host` header is written by whoever is calling — a link built from it is a link an attacker chooses the address of, and this one opens an account. | `http://localhost:5173` | **Yes** for password resets |
| `ORKNUX_WEBHOOK_MAX_BODY_SIZE` | The most a webhook caller may post to `/api/webhooks/…`, written any way `DataSize` reads: `1MB`, `512KB`, `2000000`. That endpoint is open to the internet by necessity - a build server cannot sign in - and what arrives is kept several times over on the way to becoming a run's input, so its size is the one thing an anonymous caller must not get to choose. Anything larger is refused with 413 and never reaches a trigger. Raise it where a sender genuinely posts more. | `1MB` | No |
| `ORKNUX_ASYNC_REQUEST_TIMEOUT` | How long a request that answered with a promise may stay open before the container gives up on it. The container's own default is thirty seconds, which is shorter than the five minutes `orknux_news` may be asked to wait - so a wait would have been cut off by the machinery that makes it cost nothing. Longer than the longest wait, with slack. | `330s` | No |
| `ORKNUX_SESSION_TIMEOUT` | How long a session survives without being used. A fortnight, for a self-hosted tool behind an identity provider: the provider is where a leaver is disabled, and this is not the lock keeping anybody out. Shorten it where that is not true. | `14d` | No |
| `ORKNUX_SESSION_COOKIE_SAME_SITE` | `strict` where the interface is served from this origin and nothing links into it; `lax` is what lets a link from elsewhere arrive signed in. | `lax` | No |
| `ORKNUX_SESSION_COOKIE_HTTP_ONLY` | Keeps the session cookie out of reach of scripts. | `true` | No |
| `ORKNUX_LOG_FORMAT` | `plain` reads well in a terminal; `json` (one ECS object per line) is what a collector wants. Applies to the console and the file alike. | `plain` | No |
| `ORKNUX_LOG_FILE` | Console always; name a file here and it is written to as well. Use an absolute path — the working directory of a container is not somewhere anyone goes looking. | *none* (stdout only) | No |
| `ORKNUX_LOG_MAX_FILE_SIZE` | When the log file rolls. Only consulted when a file is being written. | `10MB` | No |
| `ORKNUX_LOG_MAX_HISTORY` | How many rolled files are kept. | `14` | No |
| `ORKNUX_LOG_TOTAL_SIZE_CAP` | The ceiling on all of them together — a log that grows without bound fills the disk it shares with the database. | `1GB` | No |
| `JAVA_OPTS` | Passed to the JVM. The default gives the heap three quarters of the container's memory limit. | `-XX:MaxRAMPercentage=75` | No |

Sessions are kept in the database, so signing in outlives a restart and survives
more than one replica.

## What is deliberately not configurable

The schema validation mode, Flyway's own locations, and Spring AI's schema
initialisation. Those are invariants this application is built around rather
than choices — changing one does not configure the server, it breaks it.
