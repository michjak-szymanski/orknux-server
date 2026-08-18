# orknux-server

Workspace-based agent orchestration: workflows drawn as a graph, run durably,
with the agents, models, connections and credentials a workspace holds.

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

Postgres, something to sign in against (a directory, or an OIDC provider), and
Temporal. With the default configuration the application **refuses to start**
when Temporal is not reachable — deliberately, so a deployment brought up before
its Temporal restarts until that service answers rather than accepting work it
cannot run.

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
    volumes:
      - orknux-data:/app/data      # only if attachments are on
```

## The one that matters most

| Variable | Default | |
| --- | --- | --- |
| `ORKNUX_SECRET_KEY` | *none* | Encrypts every credential this server is trusted with — provider keys, Slack tokens, MCP secrets — so the database on its own is not enough to use them. |

There is deliberately **no default**: a key committed to an image would be a key
every installation shares, which is the same as no key at all. Generate one and
keep it — `openssl rand -base64 32`. **Changing or losing it makes every stored
credential unreadable**; nothing decrypts them afterwards, and they have to be
entered again. The Doctor page under Admin tells you whether the key is set, the
right length, and whether every stored secret can be read with it.

## Database

| Variable | Default |
| --- | --- |
| `ORKNUX_DB_URL` | `jdbc:postgresql://localhost:5432/orknux` |
| `ORKNUX_DB_USERNAME` | `orknux` |
| `ORKNUX_DB_PASSWORD` | `orknux` |
| `ORKNUX_DB_MIGRATE` | `true` — Flyway migrates on start; JPA runs with `ddl-auto: validate`, so the migrations are the only thing that changes the schema |

## Signing in

`ORKNUX_AUTH_METHOD` picks one, and only one: `LDAP` (default) or `OIDC`. Both
at once would mean an LDAP password sign-in beside a provider that is meant to
be the only way in.

**LDAP**

| Variable | Default |
| --- | --- |
| `ORKNUX_LDAP_URLS` | `ldap://localhost:389` |
| `ORKNUX_LDAP_BASE` | `dc=orknux,dc=io` |
| `ORKNUX_LDAP_BIND_DN` | `cn=admin,dc=orknux,dc=io` |
| `ORKNUX_LDAP_BIND_PASSWORD` | `admin` |
| `ORKNUX_LDAP_USER_SEARCH_BASE` | `ou=people` |
| `ORKNUX_LDAP_USER_SEARCH_FILTER` | `(uid={0})` |
| `ORKNUX_LDAP_GROUP_SEARCH_BASE` | `ou=groups` — empty disables group search |
| `ORKNUX_LDAP_GROUP_SEARCH_FILTER` | `(member={0})` |

**OIDC**

| Variable | Default |
| --- | --- |
| `ORKNUX_OIDC_ISSUER` | *none* |
| `ORKNUX_OIDC_CLIENT_ID` | *none* |
| `ORKNUX_OIDC_CLIENT_SECRET` | *none* |
| `ORKNUX_OIDC_SCOPES` | `openid,profile,email,groups` |
| `ORKNUX_OIDC_USERNAME_CLAIM` | `preferred_username` |
| `ORKNUX_OIDC_ROLES_CLAIM` | `groups` |
| `ORKNUX_OIDC_DISPLAY_NAME` | `single sign-on` — what the sign-in button says |

**Access**

| Variable | Default |
| --- | --- |
| `ORKNUX_ADMIN_ROLE` | `ROLE_ADMINS` — the role that sees the Admin section and every workspace |

## Runs

| Variable | Default |
| --- | --- |
| `ORKNUX_TEMPORAL_ENABLED` | `true` — `false` runs a workflow on the calling thread, for a single-process installation with no Temporal |
| `ORKNUX_TEMPORAL_TARGET` | `localhost:7233` |
| `ORKNUX_TEMPORAL_NAMESPACE` | `default` |
| `ORKNUX_TEMPORAL_TASK_QUEUE` | `orknux-workflow` |
| `ORKNUX_TEMPORAL_RUN_TIMEOUT_HOURS` | `24` |
| `ORKNUX_TEMPORAL_STEP_TIMEOUT_SECONDS` | `300` |
| `ORKNUX_TEMPORAL_STEP_ATTEMPTS` | `3` |
| `ORKNUX_TEMPORAL_UI_URL` | `http://localhost:8233` — where "Open in Temporal" points |
| `ORKNUX_INLINE_MAX_WAIT` | `5m` — how long an inline run may park |
| `ORKNUX_SCHEDULER_ENABLED` | `true` |
| `ORKNUX_SCHEDULER_POLLING_INTERVAL` | `10s` |
| `ORKNUX_SCHEDULER_THREADS` | `4` |

## What a workspace's code may do

| Variable | Default |
| --- | --- |
| `ORKNUX_SCRIPT_TIMEOUT_MILLIS` | `5000` — a function or tool that runs longer is stopped |
| `ORKNUX_SCRIPT_STATEMENT_LIMIT` | `5000000` |
| `ORKNUX_HTTP_REQUEST_TIMEOUT_SECONDS` | `30` |

## Models and connections

| Variable | Default |
| --- | --- |
| `ORKNUX_MODEL_TIMEOUT` | `2m` |
| `ORKNUX_MODEL_CHECK_ENABLED` | `true` — periodically asks each provider whether it answers |
| `ORKNUX_MODEL_CHECK_INTERVAL` | `5m` |
| `ORKNUX_MODEL_CHECK_INITIAL_DELAY` | `30s` |
| `ORKNUX_CONNECTION_CHECK_ENABLED` | `true` |
| `ORKNUX_CONNECTION_CHECK_INTERVAL` | `5m` |
| `ORKNUX_CONNECTION_CHECK_INITIAL_DELAY` | `30s` |
| `ORKNUX_CONNECTION_PROBE_TIMEOUT_SECONDS` | `5` |
| `ORKNUX_CONNECTION_ALLOW_LINK_LOCAL` | `false` — link-local and metadata addresses are refused; turning this on lets a workspace's connection reach them |
| `ORKNUX_SLACK_ENABLED` | `true` |
| `ORKNUX_SLACK_RECONCILE_SECONDS` | `30` |
| `ORKNUX_SLACK_RETRY_FAILED_SECONDS` | `300` |

## Chat and attachments

| Variable | Default |
| --- | --- |
| `ORKNUX_CHAT_ENABLED` | `true` — `false` is final, whatever the admin screen says |
| `ORKNUX_ATTACHMENTS_ENABLED` | `true` — same |
| `ORKNUX_ATTACHMENTS_LOCATION` | `data/attachments` — **relative resolves against the working directory**, which is right on a laptop and wrong in a container. Give it an absolute path on a volume, or attachments land in a layer that goes when the container does. |
| `ORKNUX_ATTACHMENTS_MAX_FILE_SIZE_MB` | `25` |
| `ORKNUX_UPLOAD_MAX_FILE_SIZE` | `25MB` |
| `ORKNUX_UPLOAD_MAX_REQUEST_SIZE` | `26MB` |

## Sessions, HTTP and logging

| Variable | Default |
| --- | --- |
| `ORKNUX_PORT` | `8080` |
| `ORKNUX_ALLOWED_ORIGINS` | `http://localhost:5173` — where the interface is served from |
| `ORKNUX_SESSION_TIMEOUT` | `14d` |
| `ORKNUX_SESSION_COOKIE_SAME_SITE` | `lax` |
| `ORKNUX_SESSION_COOKIE_HTTP_ONLY` | `true` |
| `ORKNUX_LOG_FORMAT` | `plain` — `json` for a log collector |
| `ORKNUX_LOG_FILE` | *none* — stdout only |
| `ORKNUX_LOG_MAX_FILE_SIZE` | `10MB` |
| `ORKNUX_LOG_MAX_HISTORY` | `14` |
| `ORKNUX_LOG_TOTAL_SIZE_CAP` | `1GB` |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75` |

Sessions are kept in the database, so signing in outlives a restart and survives
more than one replica.
