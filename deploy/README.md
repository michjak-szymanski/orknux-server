# Running Orknux with Docker Compose

[`compose.yaml`](compose.yaml) in this directory is a whole Orknux, from the
images published on Docker Hub. Nothing in it is built from source and nothing
in it is a path on somebody's machine, so it is one file you copy anywhere
Docker runs.

```
curl -O https://raw.githubusercontent.com/michjak-szymanski/orknux-server/main/deploy/compose.yaml
export ORKNUX_SECRET_KEY="$(openssl rand -base64 32)"
docker compose up -d
```

Then open **http://localhost:8080** and sign in as `alice` / `password`.

The first start takes a minute or two: Temporal applies its own schema, and the
server runs its way through eighty-odd Flyway migrations before it answers
anything. `docker compose logs -f orknux-server` is where it says so.

## The secret key, before anything else

`ORKNUX_SECRET_KEY` is the one setting with no default, and the compose file
refuses to start without it rather than letting you find out later.

It encrypts every credential the server is trusted with - model provider keys,
Slack tokens, MCP secrets - so a copy of the database on its own is not enough
to use them. It has to be **32 bytes, base64 encoded**, which is what `openssl
rand -base64 32` produces; anything else fails when a credential is read, not
when the server starts.

There is no default on purpose. A key written into a file people copy is a key
every installation shares, which is the same as having no key at all.

**Changing it or losing it strands every secret already stored.** Nothing
decrypts them afterwards. They are not corrupted and they are not recoverable -
they simply have to be entered again, by hand, one at a time. So the key is
permanent from the moment you save the first credential, and it belongs in
whatever you already use to keep secrets, backed up somewhere other than the
database it protects.

The server reads it on first use rather than at startup, which is why the guard
is in the compose file: without it the server would boot, report itself
perfectly healthy, and fail the first time anybody pressed Save on a provider
key. Admin -> Doctor is the screen that says whether the key is set, the right
length, and whether every stored secret can still be read with it.

## What it runs, and why each thing is there

| Service | Required? | What it is for |
| --- | --- | --- |
| `postgres` | Yes | Everything Orknux knows. Sessions live here too, so signing in survives a restart. |
| `ldap` | Yes, or an OIDC provider instead | Somewhere to sign in against. Orknux has no user table of its own. |
| `temporal` | Yes, as configured here | What makes a run durable: it survives a restart, retries a step, and can be looked at afterwards. |
| `orknux-server` | Yes | The API and the engine. |
| `orknux-ui` | Yes | What you open. It also serves the manual at `/docs`. |
| `temporal-ui` | No, `debug` profile | Temporal's own web interface, for reading the history of a run that went wrong. |

**One Postgres, not two.** Temporal keeps its own `temporal` and
`temporal_visibility` databases in the same server and creates them itself on
first start, so a deployment runs one database rather than two.

**LDAP is not a development convenience here.** Orknux authenticates against a
directory or against an OIDC provider - one or the other, never both, because
both would mean an LDAP password for every account the OIDC provider governs.
The default is LDAP, so without a directory the sign-in screen has nothing to
ask. What *is* a development convenience is this particular directory: `alice`
and `bob`, whose passwords are the word `password`, seeded from an LDIF written
into the compose file. Replace them (see below) before this holds anything.

**Temporal is not optional either**, with the configuration in this file, and
not only in the sense that runs would misbehave: the server **exits** when it
cannot reach Temporal, rather than starting and accepting work it has no way to
run. That is why the compose file waits for Temporal to report healthy before
the server is even created, and why every service is `restart: unless-stopped` -
a deployment brought up in the wrong order restarts until Temporal answers.

You can set `ORKNUX_TEMPORAL_ENABLED=false` on the server and drop the service.
Runs then happen on the calling thread, with no retries and no resumption, and a
restart mid-run loses the run. That is what the test suite does. It is not what
a deployment should do, which is why it is not the default here.

**Temporal's web interface is optional**, and it is left out because it is a
second web interface onto the same data with no sign-in of its own. Turn it on
when you need it:

```
docker compose --profile debug up -d
ORKNUX_TEMPORAL_UI_URL=http://localhost:8233 docker compose up -d
```

The second line is what makes a run inside Orknux link out to its history.

## Only one port is published

`orknux-ui` on 8080, and that is all. The API is not published separately, and
that is deliberate rather than tidy: `orknux-ui` forwards `/api`, `/graphql` and
`/mcp` to the server, so the browser only ever talks to one origin and the
session cookie is first-party. Publishing the server as well would give you two
addresses for the same server, and a cookie set at one of them that the other
cannot use.

Postgres, LDAP and Temporal are not published either. Nothing outside this file
has any business connecting to them, and a database on a laptop's 5432 is a
database somebody else's tool will find. Add `ports:` yourself if you want
`psql` on it.

## Settings

Every one of these is optional and has a working default. Set them in your shell
or in a `.env` file next to `compose.yaml`.

| Variable | Default | What it changes |
| --- | --- | --- |
| `ORKNUX_SECRET_KEY` | *none, required* | See above. |
| `ORKNUX_HTTP_PORT` | `8080` | The port you open in a browser. |
| `ORKNUX_DB_PASSWORD` | `orknux` | The Postgres password, used by Postgres, the server and Temporal alike. Only read when the database volume is first created. |
| `ORKNUX_LDAP_ADMIN_PASSWORD` | `admin` | The directory's admin password, which is also what the server binds with. |
| `ORKNUX_AUTH_METHOD` | `LDAP` | `LDAP` or `OIDC`. |
| `ORKNUX_SERVER_TAG` | `0.4` | Which `orknux/orknux-server` image. |
| `ORKNUX_UI_TAG` | `0.4` | Which `orknux/orknux-ui` image. |
| `ORKNUX_TEMPORAL_UI_URL` | *empty* | Where a run links out to. Empty offers no links, which is right while the Temporal UI is not running. |
| `ORKNUX_ALLOWED_ORIGINS` | *empty* | Cross-origin callers to allow. Empty is correct here, since the browser only talks to `orknux-ui`. |
| `ORKNUX_TEMPORAL_UI_PORT` | `8233` | Only with `--profile debug`. |

Everything else the server understands is one environment variable on
`orknux-server`, all prefixed the same way, and
[DOCKERHUB.md](../DOCKERHUB.md) lists every one with what it does and what
happens if you say nothing.

## Which images, and where the tags come from

Both images are published from CI on every push to `main`:

- `latest` follows `main`.
- `X.Y.Z` and `X.Y` come from release tags.
- `sha-<commit>` never moves, and is the one to pin to if you want to be certain
  what you are running.

`compose.yaml` pins `0.4`, so what you bring up today is what you bring up next
week. `latest` follows `main` and moving under a running deployment is how an
upgrade happens to you rather than being something you did. Set
`ORKNUX_SERVER_TAG` and `ORKNUX_UI_TAG` to move deliberately, and to
`sha-<commit>` if you want to be certain to the commit.

Both repositories are at `0.4`/`0.4.0`, released together, and that is what
this file uses. They are meant to move together - the interface and the server
are one product released under one version - so pin them to the same number.
Check what exists before reaching for a different one:

- https://hub.docker.com/r/orknux/orknux-server/tags
- https://hub.docker.com/r/orknux/orknux-ui/tags

Both Orknux images are published for **linux/amd64 only**. They run on Apple
Silicon under Docker Desktop's emulation, slowly. Postgres, Temporal and
OpenLDAP all have native arm64 builds.

## Before this is more than a demonstration

The defaults are chosen so that the file runs when you copy it, not so that it
is safe when you leave it. In rough order of how much it will hurt to have
skipped:

1. **Keep the secret key somewhere.** Everything above.
2. **Replace the seeded people.** Delete the `alice` and `bob` entries from the
   `ldap-bootstrap` config, or point the server at a directory you already run
   by changing `ORKNUX_LDAP_URLS`, `ORKNUX_LDAP_BASE`, `ORKNUX_LDAP_BIND_DN` and
   `ORKNUX_LDAP_BIND_PASSWORD` and dropping the `ldap` service entirely. The
   bootstrap LDIF is only read when the `ldap-data` volume is empty, so editing
   it later changes nothing until that volume goes.
3. **Or use OIDC instead**, which is usually the better answer where an identity
   provider already exists: set `ORKNUX_AUTH_METHOD=OIDC`,
   `ORKNUX_OIDC_ISSUER`, `ORKNUX_OIDC_CLIENT_ID` and
   `ORKNUX_OIDC_CLIENT_SECRET`, and drop the `ldap` service. Which of the
   provider's groups grants which role is `orknux.security.role-mapping`, and it
   is YAML only - the keys are claim values full of dots and commas, and the
   environment-variable spelling of one is not something anybody should have to
   work out. Mount an `application.yml` to set it.
4. **Change the passwords.** `ORKNUX_DB_PASSWORD` and
   `ORKNUX_LDAP_ADMIN_PASSWORD` are both the obvious word.
5. **Put TLS in front of it.** Terminate in your own proxy and forward to
   `orknux-ui` on 8080. Make sure that proxy sets `X-Forwarded-For` and
   `X-Forwarded-Proto`, or the audit log attributes every action to the proxy
   rather than to the person. Then set
   `ORKNUX_SESSION_COOKIE_SAME_SITE=strict` if nothing links into Orknux from
   elsewhere; leave it `lax` if Slack or an email is expected to link somebody
   straight to a run.
6. **Stop using `temporalio/auto-setup`.** It applies the Temporal schema on
   every start, which is what makes it a one-line dependency here and what makes
   it wrong to keep: a restart should not be a migration. Run
   `temporalio/server` and apply the schema yourself with `temporal-sql-tool`.
7. **Back up the Postgres volume**, and back up the secret key separately from
   it. A backup of the database without the key restores everything except the
   credentials, which is a restore that does not work.

## Where the data lives

Four named volumes, so nothing depends on a path on your machine:

| Volume | Holds |
| --- | --- |
| `postgres-data` | Everything Orknux and Temporal know. |
| `ldap-data`, `ldap-config` | The directory, if you are using the one in this file. |
| `orknux-data` | Attached files, under `/home/orknux/attachments`. |

`orknux-data` is mounted at the server user's home directory rather than at
something tidier like `/app/data`, and that is not a style choice. The image
runs as `orknux` rather than as root; a named volume mounted on a path the image
does not already contain is created owned by root, and the server then cannot
write a single attachment. `/home/orknux` exists in the image and belongs to
that user, so the volume inherits the ownership and works. If you move it, move
it somewhere that user can write.

`docker compose down -v` removes all four. That is how you start over, and it is
also how you lose everything.

## Upgrading

```
docker compose pull
docker compose up -d
```

Flyway migrates on the way up, so the schema follows the server. JPA runs with
`ddl-auto: validate`, which means the migrations are the only thing that ever
changes the database and a mismatch is a startup failure rather than a strange
query. Take the Postgres volume first if you would mind going back.

## When it does not come up

- **`required variable ORKNUX_SECRET_KEY is missing a value`** - the guard doing
  its job. Export one, as above.
- **`orknux-ui` restarts in a loop** - nginx resolves the server's name once,
  when it starts, so it will not start before the server exists. `docker compose
  logs orknux-server` first.
- **The server sits in `starting`** - it waits for Postgres, the directory and
  Temporal to report healthy before it is even created, and then runs the
  migrations. Its healthcheck allows a minute before it starts counting.
- **The server exits and comes back, over and over** - almost always Temporal.
  It refuses to start rather than accept work it cannot run, so the loop is the
  design working; `docker compose logs temporal` is where the reason is.
- **Sign-in is refused** - `docker compose logs ldap` and check the bootstrap
  ran. It is only applied when `ldap-data` is empty, so a volume left over from
  an earlier attempt with different settings will not have it.
- **Everything works until you save a provider key** - the secret key is wrong
  rather than missing. Admin -> Doctor says which.

## This is not the development stack

The [`compose.yaml` at the repository root](../compose.yaml) is a different
thing and is not a smaller version of this one. It brings up only the
dependencies - Postgres, OpenLDAP and Temporal - because the server itself is
expected to be running from Maven on the host, and it publishes their ports so
that it can. Use that one to work on Orknux; use this one to run it.
