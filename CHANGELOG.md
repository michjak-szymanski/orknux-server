# Changelog

What changed in each release, in the words somebody upgrading would want.

Written for the person deciding whether to upgrade and what to watch for
afterwards - not a list of commits, which git already keeps, and not a list of
issue numbers, which say nothing on their own. Anything that changes what an
existing installation does is under **Changed** and says so plainly, because
that is the section people actually need.

One file for both halves of the product: the server and the interface are
released together, under one version, and a reader who has to hold two
changelogs side by side to work out what a release contains is a reader we
have failed.

## 0.6.0

### Added

- **Orknux runs on SQLite**, as well as Postgres. Which one an installation uses
  is `ORKNUX_DB_URL` and nothing else - `jdbc:sqlite:/var/lib/orknux/orknux.db`
  instead of `jdbc:postgresql://...` - and the driver, the dialect and the
  migrations all follow from it. The username and password are ignored, because
  a file has nobody to authenticate to. It exists for the installation that
  wants to run this and nothing else: no second container, no database server to
  keep, and a backup that is one file.

  **Postgres is still what a deployment should use**, and nothing about an
  existing installation changes. SQLite takes one writer at a time, so requests
  that write queue behind each other rather than run together; the file is the
  installation, so it means exactly one server process and no second node; and a
  timestamp is stored without its time zone, since SQLite has no zoned type -
  the moment is kept, the original offset is not. Everything the test suite
  covers works on both, and the suite is run against both. The README's **The
  database** section is the full list of what differs, and it is worth reading
  before choosing.

  Two things to know when pointing it at a file. The directory has to exist -
  the server creates the database, not the folder holding it, and says which
  path is missing rather than failing with a connection error. And a running
  installation writes `-wal` and `-shm` files beside the database, so a copy
  taken for a backup has to include them, or be taken with the server stopped.

- **An agent can run commands on a machine.** A new Admin -> Shell page holds
  shells: an SSH target with a host, a port, a user and a private key. Adding one
  and editing one happen on a page of their own, at `/admin/shell/new` and
  `/admin/shell/<id>`, the same as a user or a model provider - a half-written
  machine can be left and come back to, and the private key being pasted into it
  is not one stray click on a backdrop away from being lost. An agent
  granted them opens a session, is told the session's id and what the operating
  system is, gets an empty working directory of its own on that machine, runs
  commands in it, and closes the session - at which point the directory and
  everything in it is destroyed. The switch on the agent is Shells, plural,
  because from where an agent sits the question is "can I run a command
  somewhere" rather than "may I run one on build-box-3"; which machine a session
  lands on is decided when it opens, and the answer names it.

  **What contains this is the machine, and nothing in the application.** There is
  no list of forbidden commands and no classifier deciding which are safe,
  deliberately: reading a shell command and saying what it will do is not a
  problem that can be solved, and a denylist that is nearly right is worse than
  none because it tells an administrator they are protected while
  `sh -c "$(curl ...)"` walks past it. Point a shell at a virtual machine or a
  container you are willing to lose, give the account the least privilege that is
  useful, and read the audit log - every command an agent runs is written down
  there under the agent's own name, with what it exited with.

  Nothing is held open between commands. A session is a row in the database and a
  directory on the far side, and each command opens its own connection, so a
  restart or a crash loses a socket and nothing else. A session nobody closed is
  swept after two hours idle and its directory removed, which also catches the
  ones a previous process would have swept had it lived. A command that has not
  finished in a minute is stopped and says so - and says plainly that the process
  may still be running, because closing a channel does not kill one. Output past
  64 KiB is cut and says so. A non-zero exit is a result rather than an error, and
  comes back to the agent as one: `grep` finding nothing exits 1, and an
  assistant told "that failed" would apologise for a search that worked.

  Private keys and their passphrases are encrypted at rest with every other
  credential on the platform, and no query can read one back. The host each shell
  was first seen with is remembered as a fingerprint and checked on every
  connection afterwards, so a machine answering with a different key is refused
  rather than handed the key quietly; rebuilding a machine means ticking "forget
  the host key" on purpose. The status on each row is a real connection - the
  handshake, the key accepted and a command actually run - so a host that answers
  on port 22 and refuses every account reads as unreachable rather than as fine.

- **One address can be reached through a proxy without sending everything through
  it.** A new Admin -> Networking page holds proxy rules: a regular expression
  matched against the request URL, the proxy that URL goes through, an optional
  username and password for it, and a switch. The case it was built for is the
  narrow one - everything works direct except the Entra ID token endpoint, which
  the network insists is reached through a proxy - and setting a proxy for the
  whole process would have been a far larger change than that problem asked for.

  It covers everything outbound, which is the part worth trusting. Connection
  checks, workflow HTTP calls, MCP servers, model providers and the token grants
  they need, transcription and speech all build their client the same way, so
  there is no outbound call the rules do not reach. Mail is deliberately not
  covered: SMTP is not an HTTP request, and a mail server is configured by host
  on the connection itself.

  Rules are ordered and the first one that matches wins. The order is a column on
  the page with buttons to change it, and there is a box at the bottom that will
  say, for any address you paste in, which rule answers and which rules matched
  but will never fire - because a rule that looks configured and silently does
  nothing is the thing this page exists to prevent.

  Nothing about a proxy rule relaxes the address guard. The address being called
  is checked exactly as it was before, and the proxy's own address is checked by
  the same guard when the rule is saved: a proxy is where the connection actually
  lands, and a rule pointing at a link-local address would otherwise turn every
  URL it matched into a request to this host's instance metadata. Proxy passwords
  are encrypted at rest with every other credential on the platform, and no query
  can read one back.

- **An installation can be entered without a directory.** Set
  `ORKNUX_BOOTSTRAP_ADMIN_USERNAME` and `ORKNUX_BOOTSTRAP_ADMIN_PASSWORD` and one
  internal administrator is created at startup, holding the built-in
  `Administrators` role and signing in on the ordinary form. Until now there was
  no first step: an account is made by an administrator or written down when a
  provider vouches for somebody, so an installation with neither LDAP nor OIDC
  had nobody to create the administrator who could create you. Nothing about the
  account is special - it is an ordinary internal user, and internal users have
  always been checked before the directory, whatever the configured method is.
  Everybody else is then made under Admin -> Users.

  It only ever creates. A user of that name that already exists is left exactly
  as it is, password and roles alike, and the log says it was left alone: leaving
  the variables set on the tenth restart cannot reset a password somebody has
  since changed or put back a role somebody deliberately took away. The password
  has to meet the same twelve-character minimum as every other, and a shorter one
  seeds nobody rather than making an account nobody could use.

  A password in an environment variable is a compromise, and it is one you should
  undo. It is readable by anything that can see the server's environment, so it
  is a way in rather than a credential to keep: sign in, change it, and unset
  both variables. Every start says so in the log while the account still has it.
  `deploy/README.md` has the whole of it.

### Changed

- **The migrations moved into a directory per database**, from
  `db/migration` to `db/migration/postgresql`, with the SQLite schema alongside
  in `db/migration/sqlite`. Nothing changes for an existing Postgres
  installation: the migrations are the same files with the same versions and the
  same checksums, and Flyway records neither the path nor the folder. It matters
  only to anyone writing one - a schema change now has to be written twice, once
  as a numbered Postgres migration and once folded into the SQLite baseline.

- **An id that is not yours reads as one that is not real, whether you read it
  or change it.** A by-id query now answers null for
  anything the caller cannot see, so an entity in somebody else's workspace is
  indistinguishable from an id nobody ever used. Every mutation taking an id was
  still answering two ways - "No action with id 5" for a number nothing was saved
  under, "That does not exist, or you do not have access to it" for one belonging
  to a workspace the caller is not in. Same error type, different words, which is
  the whole of the leak: walking the numbers still mapped out what this
  installation holds and roughly how much of it there is, and doing it through
  mutations rather than queries changed nothing except which endpoint answered.

  Sixty-five mutations now throw exactly what the absent case already threw, in
  the same words, so there is no second message left to tell the two apart. It
  covers actions, conditions, functions, agents, skills, tools, triggers,
  workflows, runs, models, model providers, MCP servers, workspace connections,
  memories and their catalogues, variables and their catalogues, objects, and
  issues along with their comments, links, observers and files. One more is not a
  mutation at all: downloading a file attached to an issue is a plain HTTP GET
  taking a number, and it answered the same two ways. A mutation that already
  answers false for an id that is not there now answers false for one that is not
  yours, rather than raising the error that would have been the tell.

  **On upgrade**: nothing to configure. A caller holding an id it is not entitled
  to is now told the thing does not exist, which is what it is told for an id that
  never did - so a client that treated the access refusal as "ask an
  administrator" will see "not found" instead. That is the intended answer, and
  the interface has said both in the same words since 0.5.0.

### Fixed

- **The Entra ID token endpoint is checked before the client secret is posted to
  it.** Every outbound call this server makes is asked where it is going, and the
  service principal token grant was the last one that was not. The authority it
  posts to is an installation setting rather than anything a workspace member
  types, so reaching it took the administrator role already - but it is a POST
  carrying the application's client secret, and an address nobody checked is an
  address that can be a link-local one, which is where a cloud instance keeps the
  credentials of everything running on it. It is now vetted exactly as a model
  endpoint, an MCP server address and a workflow's own request are, and a refusal
  comes back as the reason the provider check and the chat show rather than as a
  line in a log.

## 0.5.0

### Added

- **An issue can be moved to another workspace**, from a **Move** button on the
  issue itself. Administrators only, because a move takes an issue out of one
  team's tracker and puts it in another's. Its comments, labels, links,
  observers and files go with it - the files properly, bytes and all, into the
  destination's own storage, so the screenshots still open. What cannot come
  with it is its number, which is per workspace: it is given one that is free
  where it lands, and the number it had is free again for the next issue filed
  where it came from. So the address people were sending each other stops
  working, and a `#4` written in some other issue goes on pointing at whatever
  holds 4 where it was written. Nothing rewrites those, because nothing can tell
  which of them meant this issue and editing what other people wrote is not this
  product's habit; instead the move is written into the issue as a comment
  saying where it came from, into both workspaces' activity, and told to
  everybody following it. The dialog says all of this before the button is
  pressed. Where something on the issue could not exist in the destination - an
  assignee or an observer that is an agent or a model of the workspace it is
  leaving - the move is refused in a sentence naming what is in the way, rather
  than quietly clearing it: an issue that arrives looking like nobody's work is
  a worse answer than being stopped. People are never in the way, since a user
  belongs to the installation rather than to a workspace, and neither is an
  `@name` in a comment for the same reason.
- **Observers on an issue**, below its labels. An issue's news reached exactly
  two audiences - whoever has it and whoever filed it - which is the right pair
  for work somebody has been handed and nobody at all for work that has not: an
  assistant filing what it found, assigned to no one because handing out work is
  not its judgement, wrote careful reports that reached an empty room. An
  observer hears everything the reporter and the assignee hear, including an
  issue being reopened long after it was closed. Anybody in a workspace can
  watch or stop watching an issue in one press; an administrator can put
  somebody else on the list or take them off. A person or an agent can observe,
  and a model cannot - observing is a statement about who reads, and a model has
  nowhere to read its news.
- **`orknux_open_issue` takes observers**, so an assistant can put a finding in
  front of somebody without assigning them the work. Naming nobody tells the
  workspace's administrators, which is the default that the silence above
  argued for; naming anybody replaces it.
- **Forgotten passwords can be reset by mail.** There is a **Reset** link beside
  the password box on the sign-in page: type the address on your account and a
  link arrives that lets you choose a new password. The link works once, stops
  working an hour after it was sent, and using it signs the account out
  everywhere it was signed in - which is the point, since the reason for
  resetting a password is usually that somebody else may know the old one.
  Only for a user this installation made up who already has a password: a
  directory or single sign-on account's password belongs to the provider, and
  there is nothing here to reset. The form answers the same sentence whichever
  it was, so it cannot be used to find out who has an account.
  **On upgrade**: two settings are needed before this does anything, and until
  they are set the form still answers politely and the log says why.
  `ORKNUX_MAIL_HOST` and `ORKNUX_MAIL_FROM` name the installation's own mail
  server - deliberately not a workspace's SMTP connection, which belongs to that
  team and would stop working the day they rotated it. `ORKNUX_BASE_URL` is the
  address the interface is reached at, since the link has to be written from
  something
  and the request's own `Host` header is written by whoever is calling.
- **Links can be added while an issue is being written**, rather than only
  after it exists, and are hung on it the moment it is filed.
- **Sorting a list of issues by last comment**, which is not the same as by
  last change: closing, relabelling and assigning all move the change time, so
  that order surfaces the housekeeping rather than the conversation. An issue
  nobody has replied to sorts last either way round.
- **An address on a user**, taken from the directory or the OIDC provider at
  sign-in and refreshed from there until somebody types their own - after which
  sign-in leaves it alone. Yours is in Preferences; an administrator can set
  anybody's, including an external user's, which is the point.
- **A run says which run it was started from**, for both kinds of re-run, and
  links back to it.
- **Run, in the workflow editor**, which starts the graph in front of you and
  takes you to the run it made. It uses the draft, deliberately: the point is to
  try what you are looking at before committing to it.

### Changed

- **A workflow switched off now stays off.** The switch on the workflows list
  was written down, audited and shown, and nothing that starts a run ever read
  it: a workflow somebody had turned off still answered its trigger, still ran
  on its schedule, and still started when an agent asked for it by name. It now
  means what it says. Off is off for everything that starts by itself - a
  trigger, the clock, a tool call - and the trigger's own log says so, with a
  new **Workflow switched off** outcome naming the workflow it left alone,
  rather than a silence indistinguishable from a trigger that never fired.
  Pressing Run yourself still works, in the editor and in the list, because
  switching a misbehaving workflow off and going in to fix it is the ordinary
  way this is used, and refusing to try the graph would leave you turning it
  back on - live, half-fixed - just to test it. The editor says **Switched
  off** beside the workflow's name so that is a decision rather than a
  surprise, and the list stops promising a next run for one that will not have
  one.
  **On upgrade**: nothing to configure, but a workflow left switched off some
  time ago and quietly running anyway will stop the moment this is installed.
  If something you rely on goes quiet, its workflow is off - the workflows list
  shows which, and the trigger's firing log will say it turned the firing down.
- **An OIDC bearer token is now checked against who it was issued for, and this
  one can lock people out.** Only the issuer was checked, so any token the
  provider minted was accepted here - including one issued to a different
  application registered in the same Keycloak realm or Entra tenant. Roles come
  from a claim, so a group called `admins` in that other application's token made
  its holder an administrator here. A token must now name this installation in
  its `aud` claim.
  **On upgrade**: browser sign-in is unaffected, and so is any provider that
  writes the client id into the tokens it mints for this application. Bearer
  calls stop working where it writes something else - Keycloak names `account`
  unless an audience mapper is configured against this client, and Entra names
  the application's App ID URI rather than its client id. What an operator sees
  is a 401 on API calls that worked yesterday, with `The aud claim is not valid`
  in the server log. Either configure the provider to name this client, or set
  `orknux.security.oidc.audiences` (`ORKNUX_OIDC_AUDIENCES`) to what the tokens
  actually carry - it takes a list, and a token has to match one of them rather
  than all.

### Fixed

- **Waiting for tracker news no longer costs a thread.** `orknux_news` can be
  asked to hold its call open for up to five minutes, and it did that by sitting
  on the thread that took the request. Tomcat has two hundred of those and
  anybody who can sign in can ask for the longest wait there is, so a couple of
  hundred such calls took the whole server off the air - through the one tool
  whose entire purpose is waiting. The call is still held open and behaves
  exactly as before; between one look and the next it now holds no thread, no
  transaction and no database connection. A request that waits this long outlives
  the container's default patience, so `ORKNUX_ASYNC_REQUEST_TIMEOUT` sets how
  long a request answered this way may stay open, defaulting to `330s`.
- **A webhook body has a size limit.** The webhook endpoint is open to the
  internet by necessity - a build server cannot sign in - and it accepted a body
  of any size, which then became a string, a tree, a copy of the tree, its
  serialisation and a row in the run's input. An anonymous caller chose the size
  of all five. `ORKNUX_WEBHOOK_MAX_BODY_SIZE` now bounds it, defaulting to `1MB`,
  which no real webhook approaches; anything larger is refused with 413 before it
  is read, and never reaches a trigger or its history. Raise it where a sender
  genuinely posts more.
- **Signing in can no longer be tried without limit.** `POST /api/session`
  counted nothing, so a username somebody knew existed could be guessed at as
  fast as the network allowed - and under LDAP every attempt landed on the
  directory as well. Wrong passwords are now counted per username and per
  address: the first few cost nothing, after which there is a pause that doubles
  and then stops. Nothing locks: the pause always ends, a successful sign-in
  clears the count, and so does a quarter of an hour of quiet, so nobody can be
  shut out by somebody else guessing at their name badly on purpose. Somebody
  made to wait is told so with a 429 and how long to leave it.
  `ORKNUX_SIGN_IN_PER_USERNAME` (5), `ORKNUX_SIGN_IN_PER_ADDRESS` (20),
  `ORKNUX_SIGN_IN_FIRST_WAIT` (2s), `ORKNUX_SIGN_IN_LONGEST_WAIT` (5m) and
  `ORKNUX_SIGN_IN_FORGET_AFTER` (15m) change it. The counts are kept in the
  process, so two instances each keep their own.
- **The files sent into a chat are as private as the chat.** A chat belongs to
  whoever started it, but the documents attached to one were checked against the
  workspace: anybody who could see the workspace could list the attachments on
  somebody else's conversation and download them. They are now checked against
  the chat, and a file still waiting in a composer belongs to whoever uploaded
  it until the message carrying it is sent. An issue's files are unchanged -
  those belong to the people working the issue, which is the whole workspace.
- **A refusal no longer names the workspace it is protecting.** "You do not have
  access to workspace "frontend"" answered a question nobody may ask: any id
  that happened to belong to another workspace handed over its name, and GraphQL
  reports errors with a 200, so trying every id in turn is a script. It now says
  only that the thing does not exist or is not the caller's, and arrives as not
  found rather than forbidden - which is what the REST side has always answered
  to the same refusal.
- The `@` mention list appeared at the bottom of the whole editor box rather
  than at the mention. In the comment box, which sits low on the page, that put
  it off the bottom of the window with only the first two names reachable.
- The sort control's options did not name the field they sorted on - "Newest"
  sorted by number and read as a date, so a list ordered correctly looked wrong
  against the times beside it.

## 0.4.0

### Added

- **Links on an issue.** An address gets a row of its own rather than a
  sentence buried in the description, so what a report points at can be seen at
  a glance and taken off one at a time. A GitHub address is shown the way people
  say it - `owner/repo#123` for an issue or pull request, `owner/repo@abc1234`
  for a commit - worked out from the address alone, so nothing here asks GitHub
  anything. Only `http` and `https` are ever kept, because what goes on the page
  is an anchor other people click.
- **A run can be started again from one of its steps**, rather than only from
  the beginning. The steps ahead of the chosen one are not performed a second
  time: they appear as what they were, marked as carried over, and the run
  starts holding exactly what the earlier one held when it reached that point.
  It refuses, and says which, where that cannot honestly be done - a step that
  never ran, a node the graph has since lost, a branch the earlier run did not
  take.
- **A compose file that brings up a whole Orknux**, at
  [`deploy/compose.yaml`](deploy/compose.yaml): the published images, the
  database, a directory and Temporal, on one published port. The compose file at
  the root of the repository is a different thing and stays that way - it brings
  up the dependencies for working on Orknux, not for running it.

### Fixed

- An issue in a list read "opened by alice - 18 minutes ago" beside the time it
  last *changed*, which on a list of closed issues is when each was closed. Down
  a list correctly ordered by number the times then ran in no order at all,
  which is indistinguishable from sorting being broken. It shows when the issue
  was opened, and when the sort is by last change it shows that too.
- **Spring Boot 4.0.7**, which closes two things worth naming. Spring LDAP
  accepted a valid username with an empty password - an unauthenticated bind -
  on exactly the mechanism this product uses to sign people in, stopped until
  now only by a guard in a different library. And Tomcat had a request smuggling
  hole, which matters here because the interface's nginx sits in front of it and
  the boundary being crossed is the one people authenticate across.
- The volume example for the server image mounted a path the image does not
  contain, so it was created owned by root and the server - which runs as its
  own user - could not write a single attachment.
  **On upgrade**: if you copied that example, move the volume to
  `/home/orknux` and set `ORKNUX_ATTACHMENTS_LOCATION` accordingly.

## 0.3.0

### Added

- **An issue tracker in every workspace.** A list with Open, In progress and
  Closed, one search across titles, descriptions and labels together, labels
  that filter by being clicked, sorting by number, title or last change, and a
  page size you choose. The filters live in the address, so a filtered list is
  a link somebody can send. An issue carries a description, labels, a reporter,
  an assignee that may be a person, an agent or a model, comments with markdown
  and `@` mentions, and files - including a screenshot pasted straight in.
- **A notification bell**, beside the account menu. It reports an issue you
  filed changing state, comments on issues that concern you, and your name
  written in one, across every workspace you can see.
- **Mail.** An SMTP connection holds the server details, with its password
  encrypted like every other credential, and a Send Email action takes a
  recipient, subject, body, cc and reply-to.
- **The tracker over MCP**: an assistant can list, read, open, comment on,
  label and close issues, and wait on a feed of what has happened.
- **Internal users with passwords, and `orkx_` access tokens** for reaching the
  API and the MCP endpoint as a named person.
- **A workflow editor that can be undone.** Undo and redo, rebindable
  keystrokes, definition pickers you type into, components created in a panel
  beside the canvas rather than a modal over it, nodes that can be turned so a
  graph runs down the screen, and a save shortcut.
- **Voice mode says what it is doing** - listening, thinking or speaking - and
  can be interrupted mid-answer.

### Changed

- **Publishing means something now, and this is the one to read twice.**
  Publishing a workflow takes a copy, and a trigger, a schedule or the API runs
  that copy. Editing and saving change the draft only; Run in the editor uses
  the draft, because that is the graph in front of you. A workflow that has
  never been published has nothing to run and says so.
  **On upgrade**: a workflow already marked published is copied on its first
  run, so nothing stops working. A workflow that was never published, and was
  running because nothing checked, will stop - publish it once.
- **Re-running a run repeats the graph that ran**, rather than whatever is
  being edited now.
- A link into the interface no longer opens in a new tab; a modified click
  still does, because these are real links now.

### Fixed

- Deleting a role a workspace depended on removed it silently, taking access
  with it. It is refused, and names the workspaces in the way.
- The admin settings page was offered to anybody signed in. Administrators
  only, as every other admin page already was.
- "No errors", "Formatting valid" and "Schema compile healthy" were the values
  those editors opened with, before anything had been checked, and they
  survived every edit afterwards. They start as "not checked yet" and return
  there whenever the content changes.
- An oversized upload, or one to an installation with attachments switched off,
  answered 500 and the words "Internal Server Error". Both now say what
  happened.
- The workflow editor's mapping labels could not be dragged - the node beneath
  took the press - and a label that did move left its line behind.
- An agent node's output was named `reply` by a placeholder and by nothing
  else, so the node declared nothing and nothing downstream could point at it.
- Long pages grew instead of scrolling, pushing the attribution bar off the
  bottom.
- Sorting a list of issues by title failed outright, because the query joined
  the labels and Postgres will not order a distinct select by an expression
  outside its select list.

## 0.2.0 and earlier

Not written down. The changelog starts here, which is the honest place to start
it: reconstructing releases from their commits afterwards produces something
that reads like a changelog and is nobody's account of what happened.
