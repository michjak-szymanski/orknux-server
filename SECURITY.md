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
  encrypted with `ORKNUX_SECRET_KEY` and are never returned by the API except
  through the explicit reveal mutations, which are themselves access-checked.
  Anything that returns one otherwise is a vulnerability.
- **What an agent may do.** Agents run JavaScript tools and may be granted
  access to this installation. Escaping what the workspace granted counts.
- **The audit log.** A change that a workspace cannot see in its own audit log
  is a defect worth reporting.

## What is not

- Findings against a deliberately misconfigured installation — `ORKNUX_SECRET_KEY`
  unset, LDAP open to the world, the admin role granted to everyone.
- The development fixtures in `docker/ldap/bootstrap.ldif`. Those credentials
  are in this repository on purpose and are of no use anywhere else.
- Denial of service by volume against an installation you host yourself.
- Reports from automated scanners with no working exploit behind them.

## Supported versions

Fixes go onto `main`, and into the next release from it. This project has no
long-term support branches yet; when it has, they will be listed here.
