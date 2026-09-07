# orknux-shell contract (v1)

The interface between Orknux and `orknux-shell`. Orknux is the client; a v1
`orknux-shell` is a proxy in front of DesktopCommander; a later `orknux-shell`
may reimplement DC natively. **Neither side may change the shapes below without
changing the other.** As long as they hold, Orknux cannot tell a proxy from a
native reimplementation.

The Orknux side lives in `modules/connection/.../shell/McpShellClient.kt`. Read
it alongside this doc — it is the whole of what Orknux calls.

## Transport & auth

- **Streamable HTTP MCP.** One endpoint. No stdio: the point of `orknux-shell`
  is that Orknux reaches it over HTTP with no bridge on our side.
- **Bearer token** on every request: `Authorization: Bearer <token>`,
  configured at deploy. Reject anything unauthenticated. (Same shape as
  Orknux's own `orkx_...` tokens.)
- Installation-scoped: one `orknux-shell` serves the whole installation, the
  way an admin proxy rule does. It is not per-workspace.

## Tools — all of them, passed through

Orknux surfaces **every tool `orknux-shell` advertises** to the granted agent,
and forwards the agent's tool calls straight through `McpClient` (carrying the
bearer credential and obeying the proxy rules). `orknux-shell` owns its own tool
surface — process tools (`start_process`, `read_process_output`), file tools
(read / write / edit), search (`search_code`), whatever else it offers. Orknux
does not curate, rename, or filter the list; it relays.

This is the point of the backend being a coding harness rather than a terminal:
the agent gets real file-editing and search tools, not just a command line.

**Requirements on the tool surface:**

- **Stable `tools/list`.** Whatever tools `orknux-shell` reports, it reports the
  same names and JSON schemas across a session; the agent binds to them.
- **DC-compatible where it overlaps.** v1 proxies DC, so the DC tool names and
  I/O shapes pass through as-is. A later native `orknux-shell` MUST keep those
  same names and schemas, so an agent that learned the DC surface keeps working.
- **Errors as MCP errors.** A tool that cannot run comes back as an MCP error;
  `McpClient` renders it as `{ "error": ... }` to the agent.

## Sessions & isolation — required

Each agent session gets its **own directory, isolated from every other
session**. Because the exposed tools (file read/write/edit, search) take
absolute paths, Orknux cannot enforce this by passing a `cwd` — **`orknux-shell`
must enforce it.** This is the one piece of behaviour the proxy has to add on
top of DC rather than pass through.

**Keyed on the MCP session.** One MCP transport session = one jailed workspace:

1. Orknux opens a fresh Streamable-HTTP MCP session per agent shell session.
2. On session open, `orknux-shell` allocates a private throwaway root directory
   and **confines every tool call on that session to it** — a path outside the
   root is refused or remapped, and the process tools start there. The agent
   sees that root as its working world.
3. On session close (or transport drop / idle timeout), `orknux-shell` destroys
   the root and everything under it.

This keeps tool schemas untouched (no per-call session argument — the jail is a
server-side property of the transport session), so the DC-compatible surface is
preserved while each session stays sandboxed. A native reimplementation must
keep the same per-session-root guarantee.

**Consequence for Orknux:** the shell session id becomes the MCP session
Orknux holds open for that agent; `ShellSessionService.open/close` map to
opening and closing that MCP session rather than to `mkdir` / `rm -rf` commands.

## Open questions for the `orknux-shell` author

- Does DesktopCommander run cleanly **headless in Docker**, model-agnostic (no
  LLM inside)? This was the original doubt that led to building `orknux-shell`
  instead of embedding DC directly.
- Confirm DC's `start_process` returns the working directory's contents durably
  enough to satisfy semantic (2) above, or wrap it so it does.
