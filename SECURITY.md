# Security

## Reporting a vulnerability

Report it privately, through GitHub's **[Report a vulnerability](https://github.com/michjak-szymanski/orknux-server/security/advisories/new)**
form on this repository. That opens a private advisory only the maintainers can
read, which is the point: a vulnerability filed as a public issue is a
disclosure, and every self-hosted installation is exposed for as long as it
takes to ship a fix.

Please do not open a public issue, a pull request, or a discussion for something
you believe is exploitable.

What helps, in rough order of usefulness:

- what an attacker gets — read another workspace's data, escalate to admin, run
  code on the server;
- the smallest sequence that shows it, against a development installation;
- the version, or the commit, you saw it on;
- whether it needs a signed-in account, and what that account may see.

You will get an acknowledgement within a few days. Fixes are released as a
normal version, and the advisory is published once installations have had a
chance to take it.

## What is in scope

This repository, and the front end in
[orknux-ui](https://github.com/michjak-szymanski/orknux-ui) that talks to it.

Some things are worth naming, because they are what this software is for:

- **Workspace isolation.** A person sees a workspace only if their directory
  groups grant it. Anything that reads or writes across that line — through the
  API, an agent, the MCP server, or a workflow — is a vulnerability.
- **Stored credentials.** Provider keys, Slack tokens and MCP secrets are
  encrypted before they reach the database and are never returned by the API
  except through the explicit reveal mutations, which are themselves
  access-checked. Anything that returns one otherwise is a vulnerability. What
  the encryption is and is not for is the section below.
- **What an agent may do.** Agents run JavaScript tools and may be granted
  access to this installation. Escaping what the workspace granted counts.
- **The audit log.** A change that a workspace cannot see in its own audit log
  is a defect worth reporting.

## What is not

- Findings against a deliberately misconfigured installation — LDAP open to the
  world, the admin role granted to everyone, or an installation left with no key
  at all by supplying none and emptying `ORKNUX_SECRET_KEY_FILE` to turn
  generation off.
- The development fixtures in `docker/ldap/bootstrap.ldif`. Those credentials
  are in this repository on purpose and are of no use anywhere else.
- Denial of service by volume against an installation you host yourself.
- Reports from automated scanners with no working exploit behind them.

## The encryption key, and what it is worth

Every credential this server holds is encrypted with AES-256-GCM before it
reaches the database. The key comes from one of three places, and which one it
is decides what the encryption is actually defending.

`ORKNUX_SECRET_KEY` is read first, and a key supplied that way is never written
down anywhere. Otherwise the file at `ORKNUX_SECRET_KEY_FILE` is read. Otherwise
a key is generated on the first start and written to that path, with owner-only
permissions, and read back on every start after it.

Generation exists so that an installation started with nothing configured still
encrypts what it stores. The alternative was worse and was what used to happen:
the server came up, reported itself healthy, and failed the first time somebody
saved a credential — or, in the versions before that, wrote them all in the
clear. Nobody reads a manual before the first `docker run`, so the thing that
happens when nobody configures anything has to be the safe thing.

**Be clear about what a generated key buys.** It sits on the same disk as the
database it protects, so it defends the database and not the machine. A stolen
`pg_dump`, a backup that walked, a disk pulled from a decommissioned host, a
replica somebody was given read access to — against all of those the encryption
is real, and the credentials in what they took are unusable. Against anyone who
can read the host itself it is not a control at all: the key is a file the
server can open, so it is a file they can open. It is a smaller claim than
"credentials are encrypted at rest" usually implies, and it is the true one.

`ORKNUX_SECRET_KEY` is what raises it, and it is what a deployment should set.
The key then lives wherever that deployment already keeps secrets, the disk
never holds a copy, and reading the database no longer gets anyone anywhere near
it. Beyond that the key has to come from somewhere the application only borrows
it — a KMS, a vault, an operator's hands at start-up — which is a different job
and a different deployment, and which this server does not do today.

Two consequences worth writing down, because they are the ones that hurt:

- **The key and the database are one thing.** Back them up together, restore
  them together, and keep the copies apart — an archive holding both is an
  archive that hands over the credentials. A database restored without its key
  has secrets in it that nothing recovers; they are not corrupted and they are
  not recoverable, and they have to be entered again by hand.
- **A key that does not survive a restart is worse than no key.** The next start
  generates a different one, everything looks healthy, and nothing written
  before it can be read. That is why the generated key goes on a volume in every
  image and compose file this project ships, and why an installation that moves
  the path should move it somewhere just as durable.

Admin → Doctor reports whether the key is usable and whether every stored secret
still reads with it, which is the check to run after a restore rather than
waiting for somebody to open a connection and find it broken.

## Supported versions

Fixes go onto `main`, and into the next release from it. This project has no
long-term support branches yet; when it has, they will be listed here.
