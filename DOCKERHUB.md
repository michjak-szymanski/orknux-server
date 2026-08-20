# orknux-server

Workspace-based agent orchestration: workflows drawn as a graph, run durably,
with the agents, models, connections and credentials a workspace holds, and an
issue tracker beside them that an assistant can work through over MCP.

This image is the API and the engine. The interface people sign in to is
[`orknux/orknux-ui`](https://hub.docker.com/r/orknux/orknux-ui), which talks only
to this.

- **Source:** https://github.com/michjak-szymanski/orknux-server
- **Licence:** AGPL-3.0-or-later
- **Exposes:** `8080`
- **Runs as:** `orknux`, not root
- **Tags:** `latest` follows `main`; `X.Y.Z` and `X.Y` come from release tags;
  `sha-<commit>` never moves.

## What it needs

A database - Postgres, or SQLite and no second container - something to sign in
against (a directory, or an OIDC provider), and Temporal. With the default
configuration the application **refuses to start** when Temporal is not
reachable, so a deployment brought up before its Temporal restarts until that
service answers.

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

The volume goes on the server user's home directory rather than somewhere tidier
like `/app/data` on purpose: a named volume on a path the image does not already
contain is created owned by root, and this image runs as `orknux`, which cannot
then write to it. Move it somewhere that user can write, if you move it.

A whole installation - database, directory and Temporal alongside this - is
[`deploy/compose.yaml`](https://github.com/michjak-szymanski/orknux-server/blob/main/deploy/compose.yaml)
in the source repository.

## Reading the tables

Every setting is one environment variable, all prefixed the same way, so
`env | grep ORKNUX_` is the whole of an installation's configuration.

**Required** means: will this installation be wrong without it?

- **Yes** - set it, or the thing it configures does not work.
- **No** - the default is a real answer; change it when you want something else.
- **Conditional** - required only in the case named, and ignored otherwise.

The defaults are development defaults - a laptop against a local Postgres, LDAP
and Temporal. Several are wrong in a deployment, and those say so.

## The one that matters most

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_SECRET_KEY` | Encrypts every credential this server is trusted with - provider keys, Slack tokens, MCP secrets - so the database alone is not enough to use them. 32 bytes, base64. | *none* | **Yes** |

There is deliberately **no default**: a key committed to an image is a key every
installation shares, which is the same as no key at all. Generate one with
`openssl rand -base64 32` and keep it somewhere other than the database it
protects. **Changing or losing it makes every stored credential unreadable**,
and they have to be entered again. Admin -> Doctor says whether the key is set,
the right length, and whether every stored secret still reads with it.

## Database

Postgres or SQLite. `ORKNUX_DB_URL` decides which, and nothing else does - the
driver, the dialect and the migrations all follow from it.

Postgres is what a deployment should use: it takes more than one writer, and it
is what this is tested against. SQLite is a single file with nothing else to run,
for an installation of one or a few. What that costs - one writer at a time, one
machine, a backup taken while nothing writes - is the README's **The database**
section.

Under SQLite, give the file a path on a volume that outlives the container and
make sure the directory exists: the server creates the file, not the directory,
and says so by name.

```
ORKNUX_DB_URL: jdbc:sqlite:/data/orknux.db
```

The schema is Flyway's either way, and JPA runs with `ddl-auto: validate`, so the
migrations are the only thing that ever changes it.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_DB_URL` | The JDBC URL, and what picks which database. `jdbc:postgresql://host:5432/orknux` or `jdbc:sqlite:/data/orknux.db`. | `jdbc:postgresql://localhost:5432/orknux` | **Yes** in a deployment - the default is localhost |
| `ORKNUX_DB_USERNAME` | The user it connects as. Ignored under SQLite. | `orknux` | **Yes** in a Postgres deployment |
| `ORKNUX_DB_PASSWORD` | That user's password. Ignored under SQLite. | `orknux` | **Yes** in a Postgres deployment |
| `ORKNUX_DB_MIGRATE` | Whether Flyway migrates on start. Turn it off only where something else owns the schema, which this build still expects at its own version. | `true` | No |

## Signing in

`ORKNUX_AUTH_METHOD` picks one, and only one. Both at once would mean an LDAP
password for every account the OIDC provider governs - a second way in that its
policies do not reach. Below is what to set;
[the README's **Access** section](https://github.com/michjak-szymanski/orknux-server/blob/main/README.md#access)
is why.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_AUTH_METHOD` | `LDAP` or `OIDC`. | `LDAP` | No |
| `ORKNUX_ADMIN_ROLE` | The role that sees the Admin section and every workspace. | `ROLE_ADMINS` | No |

**How hard somebody may try.** A wrong password costs nothing until the
allowance is spent, then a pause that doubles to the ceiling and stops there.
Nothing locks anybody out: a success clears the record, and so does going quiet.
Username and address are counted at once, in memory, so a restart forgets both.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_SIGN_IN_PER_USERNAME` | Tries against one username that cost nothing; the next one waits. | `5` | No |
| `ORKNUX_SIGN_IN_PER_ADDRESS` | Tries from one address that cost nothing. Higher, since an address is not a person: an office behind one router is one address. | `20` | No |
| `ORKNUX_SIGN_IN_FIRST_WAIT` | The pause on the first failure past the allowance. It doubles after that. | `2s` | No |
| `ORKNUX_SIGN_IN_LONGEST_WAIT` | Where the doubling stops. | `5m` | No |
| `ORKNUX_SIGN_IN_FORGET_AFTER` | How long a quiet username or address is remembered for, so a bad afternoon does not follow anybody into the next day. | `15m` | No |

**LDAP** - read only when `ORKNUX_AUTH_METHOD=LDAP`.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_LDAP_URLS` | The directory to bind to. | `ldap://localhost:389` | **Yes** under LDAP |
| `ORKNUX_LDAP_BASE` | The root of the tree everything below is relative to. | `dc=orknux,dc=io` | **Yes** under LDAP |
| `ORKNUX_LDAP_BIND_DN` | The account this server binds as to search. | `cn=admin,dc=orknux,dc=io` | **Yes** under LDAP |
| `ORKNUX_LDAP_BIND_PASSWORD` | That account's password. | `admin` | **Yes** under LDAP |
| `ORKNUX_LDAP_USER_SEARCH_BASE` | Where people are looked for, under the base. | `ou=people` | No |
| `ORKNUX_LDAP_USER_SEARCH_FILTER` | How a typed username is matched; `{0}` is what was typed. | `(uid={0})` | No |
| `ORKNUX_LDAP_GROUP_SEARCH_BASE` | Where groups live. Workspace groups have to be under this base to be picked up; empty disables group search. | `ou=groups` | No |
| `ORKNUX_LDAP_GROUP_SEARCH_FILTER` | How a person's groups are found; `{0}` is their DN. | `(member={0})` | No |

**OIDC** - read only when `ORKNUX_AUTH_METHOD=OIDC`. Two flows at once: a browser
comes back from the provider with a code and gets the session cookie a password
sign-in issues; a script presents the provider's token as a bearer, validated
per request.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_OIDC_ISSUER` | The provider. Its discovery document says where the endpoints are and which keys sign its tokens, so none of that is written here to go stale. | *none* | **Yes** under OIDC |
| `ORKNUX_OIDC_CLIENT_ID` | This installation, as the provider knows it. | *none* | **Yes** under OIDC |
| `ORKNUX_OIDC_CLIENT_SECRET` | Its secret. | *none* | **Yes** under OIDC |
| `ORKNUX_OIDC_SCOPES` | What is asked for, comma separated. | `openid,profile,email,groups` | No |
| `ORKNUX_OIDC_USERNAME_CLAIM` | The claim to show as somebody's name. The subject is the fallback, being stable and unreadable. | `preferred_username` | No |
| `ORKNUX_OIDC_ROLES_CLAIM` | The claim carrying group or role membership; there is no standard one. Keycloak and Okta usually say `groups`, Entra `groups` or `roles`. Each value is treated as an LDAP group. | `groups` | No |
| `ORKNUX_OIDC_DISPLAY_NAME` | What the sign-in button says, in the words the people signing in use. | `single sign-on` | No |
| `ORKNUX_OIDC_AUDIENCES` | Which audiences a bearer token may name, comma separated. **Read the paragraph below before upgrading an OIDC installation.** | *the client id* | **Conditional** - see below |

**`ORKNUX_OIDC_AUDIENCES` is the one that can lock people out on upgrade.** A
bearer token has to name this installation in its `aud` claim, and empty means
the client id - which is not what two common providers write: **Keycloak** names
`account` unless an audience mapper is configured against this client, and
**Entra** names the application's **App ID URI**, `api://…`. The claim has only
been checked since **0.5.0**, so an installation coming from 0.4 or earlier
accepts those tokens today and refuses them after the upgrade: **a 401 on every
API call that worked yesterday**, with `The aud claim is not valid` in the log.
Browser sign-in is unaffected. Either configure the provider to name this
client, or set this to what its tokens carry - it is a list, and one entry
matching is enough.

Which of the provider's names grants which of this installation's roles is
`orknux.security.role-mapping`, **YAML only** because the keys are group DNs and
claim values, full of dots and commas. Empty works: a role with no mapping is
granted to whoever holds an authority derived from its own name.

**The first administrator.** An installation with neither a directory nor an
OIDC provider has nobody to create the administrator who could create you. Set
both of these and one internal administrator is created at startup, with the
built-in `Administrators` role and a password on the ordinary sign-in form,
which internal users may always use whatever `ORKNUX_AUTH_METHOD` says. It only
ever creates, so an existing account of that name is untouched; and a password
in a variable is a way in rather than one to keep, so sign in, change it, and
unset both.
[`deploy/README.md`](https://github.com/michjak-szymanski/orknux-server/blob/main/deploy/README.md#signing-in-without-a-directory)
has the rest.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_BOOTSTRAP_ADMIN_USERNAME` | The first administrator's username, created at startup if no user has it. Empty seeds nobody. | *none* | **Yes** with no directory and no OIDC |
| `ORKNUX_BOOTSTRAP_ADMIN_PASSWORD` | What they sign in with the first time. At least 12 characters, since a shorter one seeds nobody. Change it from inside and unset this. | *none* | With the above |

**Resetting a forgotten password** - a link mailed to the address on the
account, good once and for an hour, and only for an internal user who already
has a password: a directory or OIDC account's password belongs to the provider.
Until the mail server below and `ORKNUX_BASE_URL` are set it is off, and the
form answers everybody the same polite sentence while the log says what is
missing.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_MAIL_HOST` | The relay this server sends its own mail through - the installation's own, not a workspace's. Empty means no password reset and no issue mail. | *none* | **Yes** to send mail |
| `ORKNUX_MAIL_FROM` | What the mail is from. A relay will not take a message without one. | *none* | **Yes** to send mail |
| `ORKNUX_MAIL_PORT` | Empty takes what the security below usually listens on: 587, 465 or 25. | *by security* | No |
| `ORKNUX_MAIL_USERNAME` | Empty sends without authenticating, which is what an internal relay usually wants. | *none* | No |
| `ORKNUX_MAIL_PASSWORD` | That account's password. | *none* | No |
| `ORKNUX_MAIL_SECURITY` | `NONE`, `STARTTLS` or `TLS`. STARTTLS is required rather than merely offered, so a server that stopped offering it is refused rather than sent the password in the clear. | `STARTTLS` | No |
| `ORKNUX_PASSWORD_RESET_EXPIRY` | How long a mailed link works for. It is a secret sitting in a mailbox, so what matters is how long that copy stays dangerous. | `1h` | No |
| `ORKNUX_PASSWORD_RESET_PER_EMAIL` | Requests about one address that cost nothing; the next one waits. | `3` | No |
| `ORKNUX_PASSWORD_RESET_PER_ADDRESS` | Requests from one caller that cost nothing. Higher, for the same reason. | `20` | No |
| `ORKNUX_PASSWORD_RESET_FIRST_WAIT` | The pause on the first request past the allowance; it doubles after that. | `2s` | No |
| `ORKNUX_PASSWORD_RESET_LONGEST_WAIT` | Where that doubling stops. | `5m` | No |
| `ORKNUX_PASSWORD_RESET_FORGET_AFTER` | How long a quiet address is remembered for. Counted separately from sign-in, so somebody asking about your account repeatedly cannot pause you out of the sign-in page. | `15m` | No |

## Runs

Temporal is what makes a run durable: it survives a restart, retries a step, and
can be looked at afterwards.

What a trigger, a schedule or the API runs is the workflow **as it was
published**, not as it is being edited - Run in the editor is the one thing that
uses the draft. A workflow nobody has published has nothing to run and says so.
The README's **Publishing** section is why.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_TEMPORAL_ENABLED` | `false` runs a workflow on the calling thread, with no retries and no resumption - for a single-process installation with no Temporal. The tests do that; a deployment should not. | `true` | No |
| `ORKNUX_TEMPORAL_TARGET` | Host and port of the Temporal frontend. | `localhost:7233` | **Yes** in a deployment, unless Temporal is off |
| `ORKNUX_TEMPORAL_NAMESPACE` | The Temporal namespace to run in. | `default` | No |
| `ORKNUX_TEMPORAL_TASK_QUEUE` | The queue workers take work from. Change it to run two installations against one Temporal. | `orknux-workflow` | No |
| `ORKNUX_TEMPORAL_RUN_TIMEOUT_HOURS` | How long a whole run may take, waits included. | `24` | No |
| `ORKNUX_TEMPORAL_STEP_TIMEOUT_SECONDS` | How long one step's own work may take. A model call is slow, so a step is given minutes. It does not bound what a step *waits* for: a wait parks the step. | `300` | No |
| `ORKNUX_TEMPORAL_STEP_ATTEMPTS` | How many times a failing step is tried, since most of what a step does is call something else. | `3` | No |
| `ORKNUX_TEMPORAL_UI_URL` | Temporal's own web interface, linked out to from a run. Empty offers no links, which is right where it is not exposed. | `http://localhost:8233` | No |
| `ORKNUX_INLINE_MAX_WAIT` | Only the inline engine: how long a run may spend parked in total before the step fails and says what would have carried it. A Temporal wait is a timer, bounded by the run timeout. | `5m` | No |
| `ORKNUX_SCHEDULER_ENABLED` | The clock behind scheduled triggers. Its state is in the database, so one instance fires a schedule however many are running. | `true` | No |
| `ORKNUX_SCHEDULER_POLLING_INTERVAL` | How often it looks for due work. | `10s` | No |
| `ORKNUX_SCHEDULER_THREADS` | How many due schedules it may start at once. | `4` | No |

## What a workspace's code may do

A workspace's JavaScript runs in a GraalJS sandbox with no host access, no
files, no network and no threads. These bound what it can spend.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_SCRIPT_TIMEOUT_MILLIS` | A function or tool that runs longer is stopped. | `5000` | No |
| `ORKNUX_SCRIPT_STATEMENT_LIMIT` | How many statements one may execute - what catches a loop that never ends. | `5000000` | No |
| `ORKNUX_PLUGIN_TIMEOUT_MILLIS` | The same, for a plugin, which is a bundle and takes longer to load. | `10000` | No |
| `ORKNUX_PLUGIN_STATEMENT_LIMIT` | The same, for a plugin. | `10000000` | No |
| `ORKNUX_HTTP_REQUEST_TIMEOUT_SECONDS` | How long a workflow's own HTTP request may take. A step that never returns holds the run that made it. | `30` | No |

## Models and connections

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_MODEL_TIMEOUT` | How long a model has to answer. Generous: a large local model on a laptop is slow, and giving up is worse than waiting. | `2m` | No |
| `ORKNUX_MODEL_CHECK_ENABLED` | Periodically asks each provider whether it still answers, so a status on the screen is recent rather than from whenever somebody last looked. | `true` | No |
| `ORKNUX_MODEL_CHECK_INTERVAL` | How often that sweep runs. | `5m` | No |
| `ORKNUX_MODEL_CHECK_INITIAL_DELAY` | How long after start the first sweep waits. | `30s` | No |
| `ORKNUX_CONNECTION_CHECK_ENABLED` | The same, for connections. | `true` | No |
| `ORKNUX_CONNECTION_CHECK_INTERVAL` | How often connections are checked. | `5m` | No |
| `ORKNUX_CONNECTION_CHECK_INITIAL_DELAY` | How long the first check waits. | `30s` | No |
| `ORKNUX_CONNECTION_PROBE_TIMEOUT_SECONDS` | How long a check may take to find out whether anything is listening. | `5` | No |
| `ORKNUX_CONNECTION_ALLOW_LINK_LOCAL` | Link-local addresses reach cloud instance metadata, so they are refused; turning this on lets a workspace's connection reach them. Private and loopback stay reachable either way. | `false` | No |
| `ORKNUX_CONNECTION_ENTRA_AUTHORITY` | Where an Entra ID token is asked for. The worldwide cloud - a tenant in a sovereign cloud has an address of its own. | `https://login.microsoftonline.com` | No |
| `ORKNUX_SLACK_ENABLED` | Opens one Socket Mode websocket per Slack connection holding an app-level token, and turns arriving mentions into workflow runs. | `true` | No |
| `ORKNUX_SLACK_RECONCILE_SECONDS` | How often open sockets are compared with stored connections, so a token pasted into the settings form starts listening without a restart. | `30` | No |
| `ORKNUX_SLACK_RETRY_FAILED_SECONDS` | How long a connection Slack refused is left alone. Changing the token clears the wait. | `300` | No |

**A workspace's mail is not configured here.** The `ORKNUX_MAIL_*` variables
above are the installation's own relay. Mail a *workflow* sends is a connection
like any other, typed into a workspace's connection form - separate on purpose,
since a workspace's credential belongs to one team, and a password reset has to
work for somebody in no workspace at all.

## Chat, attachments and the tracker

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_CHAT_ENABLED` | Whether this installation has a chat at all. `false` is final: an administrator may turn the chat off from the screen, but not back on where the operator said no. | `true` | No |
| `ORKNUX_ATTACHMENTS_ENABLED` | Whether files may be attached at all - to a chat message, an issue, or a comment on one. `false` is final in the same way, and hides the upload controls without hiding files already uploaded. | `true` | No |
| `ORKNUX_ATTACHMENTS_LOCATION` | Where the bytes go, one directory per workspace. **Relative resolves against the working directory**, which is wrong in a container: give an absolute path on a volume, or attachments go when the container does. | `data/attachments` | **Yes** if attachments are on |
| `ORKNUX_ATTACHMENTS_MAX_FILE_SIZE_MB` | The largest file that will be accepted, refused with a sentence rather than a stack trace. | `25` | No |
| `ORKNUX_UPLOAD_MAX_FILE_SIZE` | The servlet's own cap on one uploaded file. Keep it at or above the attachment cap, or the larger limit is never reached. | `25MB` | No |
| `ORKNUX_UPLOAD_MAX_REQUEST_SIZE` | The cap on a whole upload request - a file plus what comes with it. | `26MB` | No |

One switch and one directory for both: the tracker's attachments are the chat's,
and the tracker needs nothing else configured here.

## Sessions, HTTP and logging

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_PORT` | The port this server listens on inside the container. | `8080` | No |
| `ORKNUX_ALLOWED_ORIGINS` | Where the interface is served from, when it is not this server. Comma separated; empty allows none, which is right once they share an origin. | `http://localhost:5173` | **Yes** where the interface is elsewhere |
| `ORKNUX_BASE_URL` | Where the interface is reached from, as a browser spells it, and what a mailed password reset link points at. Configured rather than read off the `Host` header, which whoever is calling writes - and this link opens an account. | `http://localhost:5173` | **Yes** for password resets |
| `ORKNUX_WEBHOOK_MAX_BODY_SIZE` | The most a webhook caller may post to `/api/webhooks/…`, written any way `DataSize` reads: `1MB`, `512KB`, `2000000`. That endpoint is open to the internet by necessity - a build server cannot sign in - so anything larger is refused with 413, before any trigger. | `1MB` | No |
| `ORKNUX_ASYNC_REQUEST_TIMEOUT` | How long a request that answered with a promise may stay open. The container's own thirty seconds is shorter than the five minutes `orknux_news` may be asked to wait, and would cut that wait off. | `330s` | No |
| `ORKNUX_SESSION_TIMEOUT` | How long a session survives without being used. A fortnight, for a self-hosted tool behind an identity provider, which is where a leaver is disabled. Shorten it where that is not true. | `14d` | No |
| `ORKNUX_SESSION_COOKIE_SAME_SITE` | `strict` where the interface shares this origin and nothing links into it; `lax` is what lets a link from elsewhere arrive signed in. | `lax` | No |
| `ORKNUX_SESSION_COOKIE_HTTP_ONLY` | Keeps the session cookie out of reach of scripts. | `true` | No |
| `ORKNUX_LOG_FORMAT` | `plain` reads well in a terminal; `json` (one ECS object per line) is what a collector wants. Applies to console and file alike. | `plain` | No |
| `ORKNUX_LOG_FILE` | Console always; name a file here and it is written to as well. Use an absolute path - a container's working directory is not somewhere anyone looks. | *none* (stdout only) | No |
| `ORKNUX_LOG_MAX_FILE_SIZE` | When the log file rolls. Only consulted when a file is being written. | `10MB` | No |
| `ORKNUX_LOG_MAX_HISTORY` | How many rolled files are kept. | `14` | No |
| `ORKNUX_LOG_TOTAL_SIZE_CAP` | The ceiling on all of them together, since a log without one fills the disk it shares with the database. | `1GB` | No |
| `JAVA_OPTS` | Passed to the JVM. The default gives the heap three quarters of the container's memory limit. | `-XX:MaxRAMPercentage=75` | No |

Sessions are kept in the database, so signing in outlives a restart and more
than one replica.

## What is deliberately not configurable

The schema validation mode, Flyway's own locations, and Spring AI's schema
initialisation: invariants this application is built around rather than
choices - changing one does not configure the server, it breaks it.
