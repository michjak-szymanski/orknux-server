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

## Unreleased

### Added

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
