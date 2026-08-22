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

- **An agent can put a name on an issue.** `orknux_update_issue` takes an
  `assignee` — a person, an agent or a model, by the name somebody would say,
  rather than the kind-and-id pair the browser sends because it has just drawn
  the list to pick from. `"nobody"` hands it back. A name that matches nobody is
  refused by name rather than ignored, because an update that answers as though
  it worked and leaves the wrong person on the issue is the failure nobody goes
  looking for.

### Fixed

- **The status that says somebody has started is no longer hidden from the
  agents.** `orknux_set_issue_status` accepted `IN_PROGRESS` all along and
  described itself as *"Opens or closes an issue"*, with its one parameter
  reading *"OPEN or CLOSED."* — so a model with three values available sent one
  of two, every time. A capability nobody is told about is worse than a missing
  one: missing gets noticed.

- **An issue somebody picked up is no longer audited as reopened.** Both doors
  wrote the line as `if (wanted == CLOSED) "closed" else "reopened"` — two
  answers to a question with three — so the browser and the tools alike recorded
  every issue anybody started as having been reopened. The wording now lives
  beside the enum, decided once for all three values, and knows that only a
  closed issue can be reopened: one moved back from in progress was put down,
  which is a different thing to have happened.

- **An agent no longer answers a later question out of its own summary of a
  lookup.** What a tool returned was threaded into the round that fetched it and
  the round was thrown away: the provider's loop resolves the calls and only the
  text the model wrote out of them is kept. So on the next turn the model held
  its account of the data rather than the data, and "check that again" was
  answered from prose. It showed up as two models on one conversation both
  insisting that issues were unlabelled when they plainly carried a label, each
  correcting itself only when it called the tool again.

  A call and what it returned are now one line of the session, and what tools
  returned lately goes back in front of the model beside what was said — as
  data, labelled as data, with a budget of its own rather than a share of the
  turns' allowance. That budget is the point: a single listing of one
  workspace's issues measured forty thousand characters against twenty-four
  thousand for everything anybody had ever said, so a result sharing that
  allowance either takes all of it or is the first thing dropped. The newest
  lookups are kept, the same lookup made twice counts once, and a result too
  large to hand back whole is cut with the cut saying so and naming the tool to
  call for the rest — a model told it holds half an answer can go and get the
  other half, which a silently shortened list never lets it do.

  This reaches workflows and chats alike. **A chat with an agent now keeps a
  session of its own**, which is where its lookups survive; it appears in the
  session list under the `chat` prefix. A chat with a bare model calls no tools,
  has nothing to keep, and still opens nothing.

## 0.9.0

### Added

- **A node's retry policy is a policy, not a checkbox.** Attempts and a single
  wait with a "double it" tick have become attempts, an initial wait, a
  multiplier, a ceiling, jitter and a total budget. A multiplier of 1 is a fixed
  wait and 2 is what the tick used to do, so everything in between is now
  sayable; the ceiling stops a long policy growing to an absurd delay in
  silence; and the budget is the one people actually reach for, because what
  happens *between* two waits is a call to something outside this installation,
  and five attempts at a request that times out after a minute is five minutes
  no arrangement of waits accounts for.

  Jitter is a fraction rather than a switch, and is only ever subtracted — so
  the ceiling and the budget still mean what they say. The panel shows three
  fields and a sentence — *"Up to 5 attempts over about 2m"* — with the rest
  behind a disclosure, and **a graph you already have does exactly what it did
  before**.

- **An HTTP action's headers are built as rows, and a value may name a
  variable.** They were a JSON blob typed by hand, so a bearer token had to be
  pasted in as a literal — into a field that is not a credential field, stored
  unencrypted, visible to anyone who could open the action. A referenced value
  is read when the action runs and is shown nowhere: not in the form, not in a
  log, not in an error. Deleting a variable an action's header names is now
  refused, as it already was for a function.

  Headers saved before this keep working and are left in the database exactly as
  they were until somebody saves the form.

- **Three times as many icons to label a node with**, 94 to 291, chosen by what
  the nodes in this product actually reach — tickets, source control,
  infrastructure, storage, security, money, documents, logistics. The furniture
  rule that hides chevrons and arrows was also hiding `play`, `save`, `search`,
  `plus` and `copy`, which are perfectly good names for a node; they are back.


- **The workflow list can be sorted, and shows as many rows as you ask for.**
  Name, last run, or switched on, in either direction, with the same 10/25/50/100
  chooser the issues list has. Sorting is done by the server, so it orders the
  whole list rather than the page you happen to be looking at; the order lives in
  the address, so a sorted list can be shared.

  The list used to open showing four rows - a number the chooser could not be set
  back to. It opens on ten now.

- **An agent's definition opens beside the graph** rather than instead of it.
  Pressing Open definition on an agent node used to navigate to the agent's own
  page and take the canvas off the screen; it now opens in the panel down the
  left, the way every other kind already did, carrying the model, the system
  prompt and every grant. Ctrl-click still opens the page in a tab.

- **The four editors ask before unsaved work is walked away from.** Function,
  tool, object and skill: clicking a link, closing the tab or pressing Back with
  changes on screen now asks, and offers to save on the way out. Typing something
  and undoing it counts as no change, and saving then leaving asks nothing -
  a guard that fires after a successful save is one people learn to click
  through.

- **Creating and closing an issue is written to the audit log** whichever door it
  came through. The page's door already recorded it; the tools an agent uses did
  not, so an issue an agent filed left no trace of who filed it or when.


- **A node can be told what to do when it fails.** An action or an agent now
  carries a retry policy - how many attempts, and how long to wait between them
  - and can be given a second way out of the node, drawn as a red **If fails**
  line beside the green **If works** one. A failed step still records its
  failure; the difference is that the run can carry on down the other line
  instead of stopping.

  The wait is a backoff rather than a number: an **initial wait** and a
  **multiplier**, so a provider asking to be left alone is left alone for longer
  each time rather than being knocked on at the same interval - 1 repeats the
  wait, 2 doubles it, and the numbers between grow it more gently. Behind a word,
  for the nodes that want them: a **maximum wait** so a long policy cannot grow
  into an absurd one, **jitter** so a hundred runs that failed together do not
  retry together, and a **budget** - give up after this long whatever you are
  doing, which is the only one that counts the work as well as the waiting.

  Under the fields is a line saying what they come to, because five numbers that
  each read as small compose into something nobody works out in their head.

  A failure already known to be final never spends an attempt. A thrown function
  will throw the same way next time, and so will a provider that refused the
  request it was sent; only the failures that might come out differently - a
  timeout, a 429, a 5xx, a dropped connection - are asked again. Worth knowing
  before you turn retries on for an agent: **every attempt is another billed
  call**, and nothing caps that.

- **The chat shows the lookups an agent made**, not only the words it said, so a
  continued conversation reads as what happened rather than as an answer with no
  visible reason. What the model is sent is unchanged.

- **A turn carried into a chat says who said it**, which matters once a session
  has been picked up by hand and holds both an agent's turns and a person's.

- **Turning a node is offered on the node**, above the selected one beside its
  resize handles. `R` and the details panel still do it; all three go through
  the same action.

- **The object a function parameter names is one link away**, beside the
  selector, so the shape the code is being written against can be read without
  hunting for it.

- **Functions, tools, skills and agents keep what they were.** Every save now
  keeps the version it replaced, and each of those four has a **History** panel
  beside it: who saved it, when, what the code or the prompt said then, and a
  button that puts it back. Restoring keeps what it replaces too, so a restore
  is undone the same way it was made. Nothing before the upgrade is recovered -
  a component's history begins with the first save after it.

- **A workflow's publications are kept, and one can be put back into service.**
  They used to be one row per workflow, overwritten every time somebody pressed
  Publish, so what a workflow ran last month was unanswerable. The workflow's
  settings page now lists every publication, marks the one that runs, and
  restores an older one by publishing it again - which leaves a record that a
  rollback happened rather than deleting what came after.

  A workflow's versions are its **publications and not its saves**: a draft is a
  draft. Restoring one therefore does not touch the draft on the canvas, so no
  half-finished work is lost - what changes is what triggers and schedules run,
  and the badge says Draft while the two differ.

  **Variables are deliberately not versioned.** Their values are encrypted, and
  keeping old ones would mean keeping old secrets.

- **How long that history is kept is an Admin setting**, fourteen days by
  default (`ORKNUX_REVISION_RETENTION_DAYS`). A version is a whole copy of what
  a component was, so this is the number that decides how much disk it takes.
  It is measured from when a version stopped being current, not from when it was
  written, and a workflow's live publication is never swept whatever its age.

- **A Slack connection's app-level token can be read back.** It could be typed
  and never seen again, which left no way to check which token was in there
  short of pasting a new one. It now has its own Reveal, and the audit log
  records which of the two credentials was revealed rather than only that one
  was.

### Changed

- **Google AI is no longer a provider type; use Custom.** It never worked. The
  only thing the type ever did was send the key as `x-goog-api-key`, which is
  the header Google's own API wants - while every address orknux builds for a
  provider is an OpenAI-shaped one (`/models`, `/chat/completions`), which that
  API does not have. Pointed at Google's base it failed both; pointed at
  `/v1beta` it passed the connection check against a real model list and then
  404'd on every message, which is the worst way for it to fail.

  Google's OpenAI-compatible endpoint does work, and always has - through
  **Custom**. Set the endpoint to
  `https://generativelanguage.googleapis.com/v1beta/openai` with your Gemini key
  and the model list, chat, streaming and tool calls all behave.

  **On upgrade**, providers stored as Google AI become Custom providers with
  their endpoint and key untouched, and the models configured against them are
  left alone. They will start sending `Authorization: Bearer`, which is the
  header that endpoint documents - so a provider that could not work before may
  now, once its endpoint names the `/v1beta/openai` path. If you script against
  the API, `GOOGLE_AI` is gone from `ProviderType`.

- **The Ollama endpoint hint points at `/v1`.** The provider form suggested
  `http://localhost:11434`, which is the address of Ollama's own API; the
  OpenAI-compatible one orknux talks to is under `/v1`, and anyone who took the
  hint at its word got a 404 from both the check and the first message.

- **There is one Slack connection type, not two.** "Slack (outgoing only)" and
  "Slack (Socket Mode)" were two names for the same integration: whichever you
  picked, orknux listened on it the moment it held an app-level token. So they
  are one **Slack** now, with a required bot token (`xoxb-...`) and an optional
  app-level token (`xapp-...`) - give it the app-level token and it listens for
  mentions as well as sending, leave it empty and it only sends.

  **Nothing to do on upgrade.** Connections stored as Socket Mode become Slack
  connections with both their tokens where they were, and go on listening
  exactly as they did. If you script against the API, `SLACK_SOCKET_MODE` is
  gone from `ConnectionType` and `SLACK` is what to send.

  A Slack connection is no longer asked for its URL or how it authenticates:
  there is one Slack Web API and a bot token is a bearer token, so both are
  filled in. A connection that pointed somewhere else is moved to
  `https://slack.com/api`; a workspace's own URL override is left alone.

- **Deleting an issue asks first.** It used to delete on the click that reached
  it. If you have anything scripted against that button, it now opens a dialog.

- **The loader waits three seconds before it appears**, everywhere it is used.
  It was five, which was not a quiet period but a mute button - no screen ever
  reached it, so the loading marks the interface already had were never drawn.
  Three seconds is a deliberate choice: ordinary loads here finish well inside
  it and still pass in silence, and only a wait long enough to look broken says
  anything.

- **A line in the workflow editor takes as many bend points as you put on it.**
  Double-click the line to add one where you clicked; double-click a point, or
  press Delete with it focused, to take it off. The last one off straightens the
  line, which is what those gestures already did when a line could hold only one.

  The curve through several points passes through each of them rather than being
  pulled at by them, so dragging one still moves it by exactly as much as you
  dragged. A line with a single bend is drawn exactly as it was before, and
  arrangements you have already made are read and written unchanged - nothing
  moves by upgrading, and nothing breaks by going back.

- **Deleting something that is still in use is refused, and says what is using
  it.** Actions, agents, conditions and triggers were deletable while a workflow
  still named them - a published workflow could be left calling something that
  no longer existed, and only said so when it ran. Tools, skill catalogs and
  memory catalogs are granted to agents *by name*, so deleting one silently took
  a capability away from every agent granted it, with the grant still listed on
  the agent's screen.

  All of them now refuse with a sentence naming what is in the way. Objects are
  the deliberate exception where the reference is only an annotation - a function
  parameter or a node's shape - because losing one degrades what the editor
  writes above the code, visibly, on the screen where you would fix it. Where an
  object is a contract rather than an annotation, such as a webhook's input
  shape, the delete is refused like the rest.

- **Toggling a tool's or a skill's Active badge no longer discards the draft.**
  It applied the whole stored copy over the form, so an edit in progress was
  replaced by whatever was saved. It now reads only what it changed.

- **"Webhook" no longer names two opposite things.** A trigger of that kind is
  incoming — a path this installation exposes. A connection of that kind was
  outgoing, a URL this installation posts to, which is not a webhook: it is an
  HTTP endpoint, and whether the receiver thinks of it as a webhook is the
  receiver's business. The connection kind is now **HTTP**. Existing
  connections are carried across.

- **Jira and GitHub are no longer offered as connection kinds.** Nothing
  implemented either; both fell through to the same generic HTTP handling.
  Connections of those kinds become HTTP connections, which is what they always
  were.

- **A secret is revealed by the same control everywhere.** Some fields offered a
  green *Reveal* link and some an eye; two of the links only revealed, with no
  way to put the value back. Every one is now the eye, it toggles, and it says
  which state it is in.

- **The readmes name the other three addresses** the site answers to.

### Fixed

- **The magnifier above a conversation searches the conversation.** It searched
  the list of chat titles, which is the sidebar's question - *which chat was that
  in* - and not the one being asked above an open chat. A strip under the title
  now finds what was said in this one: it marks the turns that match, rings the
  one being looked at, counts them, steps with Enter or the chevrons, and shuts
  on Escape.

- **The footer says which version of Orknux this is.** It named the product, the
  copyright holder, the licence and the source, and not the one thing anybody is
  asked for first.

- **Deleting a chat asks first.** It did not: the trash button removed the chat
  and every message in it on one press, with nothing said, from the title bar
  and from the sidebar's row menu both.

- **The chat's search button searches.** It used to focus a hidden read-only
  input that existed only to be focused, so pressing it did nothing whatever. It
  now puts the caret in the box that actually filters the list, opening the
  sidebar first if it is shut.

- **The whole message box takes a click.** Only the narrow line of text did, so
  a press on the padding - most of what reads as the field - went nowhere. Its
  padding is smaller as well.

- **The chat's title bar is one row.** The model or agent answering sat in a bar
  of its own below the title, carrying one word; it is beside the title now, left
  of the controls that act on the chat.

- **Every icon control in the product answers the pointer**, not only the ones on
  the four pages this was first noticed on. The same small square had been
  declared under five different names across sixteen stylesheets, and a fifth of
  them did nothing at all when hovered.

- **The Add menu in the workflow editor keeps the two LLM kinds together.** Its
  order came from the order somebody had declared the labels in, which put LLM
  Agent first and LLM Session last with three unrelated things between them.

- **The small square at the end of a row lights up under the pointer, and does
  it the same way on every page.** It was declared sixteen times in sixteen
  stylesheets - identical in every respect except the one that was reported:
  three did nothing under the pointer, six lifted the border, three turned the
  brand green, two lifted the background. On one row that drew as two buttons
  that ignored the pointer beside a third that went green.

- **A dialog explains itself behind the (?) beside its title**, rather than in a
  paragraph above the form. Four of them - export, use a template, save as a
  template, add a proxy rule - had grown a paragraph where the rest of the
  product had already moved to the question mark. The last of those had four
  question marks on its own fields and one paragraph left in the open, which is
  the convention disagreeing with itself inside a single dialog.

- **Typing quickly in a code editor no longer scrambles what you typed.** The
  editor wrote its incoming text back into the document whenever the two
  disagreed — but that work happens after the screen is painted, so a keystroke
  landing in the gap left it holding the text from one character ago. It wrote
  that back and sent the cursor to the top of the file, and did it again on the
  next keystroke. At a realistic typing speed a line came out reversed in
  chunks, and **what was saved was the scrambled text**, because the document is
  what a save stores.

- **An editor no longer asks about unsaved work on a function nobody touched.**
  Opening certain functions produced "you have changes the server has not been
  told about" immediately, because the editor rewrote the code to match the
  panel as the page finished loading. The guard against that fired by counting
  renders, and the rewrite happened on a later one — after the workspace's
  variables arrived. It is asked by value now. The tool editor had the same
  fault.

- **The workflow list's first column no longer says "Template name"** on a list
  of workflows, and the sort control shares a line with the buttons beside it
  rather than taking a row of its own.

- **A line in the workflow editor accepts extra bend points even when it carries
  a label.** The label sat on the first point as its only handle, covering the
  part of the line you would aim at.

- **The line from a session node is drawn as a dependency**, dashed, like every
  other line that says "this uses that" rather than "this runs next". A session
  is not a step, and the solid line said it was.

- **The editor's side panel can be closed from its top-right corner.** The only
  way out was at the bottom, past the whole form — and the panel's first
  inch had always been hidden behind the toolbar above it.


- **A workspace can be deleted on SQLite, which is what the one-container image
  runs.** Any workspace holding an action that calls a function could not be
  deleted at all: the delete cascaded the function away, a foreign key nulled the
  column a check constraint required, and the whole thing failed with nothing on
  screen to explain it. The workspace survived and the error said only that
  something went wrong. Postgres was never affected, which is why it lasted.

  The same fault was present twice in one table - for conditions as well as
  functions - and both are fixed. Deleting a function that something still calls
  is refused by name, as it already was.

- **Twenty-seven backgrounds across the interface were painted with a colour that
  does not exist.** They were transparent, showing whatever happened to be
  behind: search boxes, label chips, the issue sidebar, the code area, a node's
  input socket. All of them now use the recessed surface they meant, in both
  themes.

- **A list that could not be fetched no longer says the workspace is empty.**
  Pickers and grant lists turned a failed request into "there are none yet",
  which is a different and untrue statement. They now say what failed and what to
  do about it - sign in again, ask an administrator, try again - and offer to
  retry that one list.


- **Pictures sent to an Anthropic model now arrive.** They did not. The request
  built for an Anthropic provider had no place for them at all, so a turn
  carrying a screenshot reached the model as words alone - nothing failed,
  nothing was logged, and the model answered plausibly and at length about
  something it had never been shown. An agent whose whole purpose was reading a
  picture appeared to be working.

  A picture is now carried as an image block, which is the shape Anthropic
  reads. A picture that genuinely cannot be carried - a format it does not
  accept - **fails with a sentence naming the format**, rather than being left
  behind. That is the part worth knowing before you upgrade: a workflow that has
  been quietly discarding pictures will now say so instead.

- **The one-container image works on whatever port you publish it on.** It did
  not. `docker run -p 9000:8080 orknux-one` gave a sign-in page that loaded
  perfectly and then refused every request it made, with a 403 and nothing on
  screen to explain it. nginx forwarded the Host header with the port removed,
  so the server rebuilt its own address without one; the page's own origin then
  no longer matched the address its requests appeared to arrive at, every
  ordinary call was treated as cross-origin, and that image allows no origins
  because it was never expected to need any. The image's own smoke test never
  saw it, because `curl` sends no `Origin` header and a browser always does.

  The interface image behind a proxy was affected the same way.

- **The proxy rules now cover the calls that were going round them.** Three
  things this installation does over the network ignored the rules on the
  networking page, each for its own reason, and each failed on exactly the
  networks the rules exist for.

  The worst was a name lookup. Before any call was made, the host was resolved
  here first - so on a network where only the proxy can resolve an external
  name, every call failed with *"The host could not be resolved"* before the
  rules were consulted at all. An installation whose proxy configuration was
  entirely correct could not make a single call, and nothing on the page listing
  those rules said why. A host a rule carries is now the proxy's to resolve. A
  host nothing carries is still checked here, and an address pointed at instance
  metadata is still refused whether or not a proxy would have fetched it.

  **Signing in with OIDC now works behind a proxy.** Discovery, the key set, the
  token exchange and userinfo were four separate clients Spring Security built
  for itself, none of which the rules reached. Discovery runs while the server is
  starting, so a proxied installation did not come up at all - and the page where
  the proxy rules are written is behind the sign-in that was failing.

  **Mail is routed too.** A rule is matched against `smtp://host:port`; if one
  names the server, the session opens a `CONNECT` tunnel through that proxy. Note
  that a proxy has to be willing to `CONNECT` to a mail port, which is not
  always the case - so if a rule is in place and mail still fails at the connect,
  that is the thing to ask about.

  Directory sign-in over LDAP is still not routed and cannot be by these rules:
  it is not HTTP, so an HTTP proxy has nothing to carry. The networking page now
  says so rather than claiming mail as the exception.

- **A chat turn no longer disappears because a tool call failed.** Asking an
  agent in chat to file an issue could take the whole turn with it - the answer
  and the person's own message both gone, replaced by an error with nothing
  written in it. The tracker tools ran inside the chat's transaction, so a
  refused write left that transaction unusable; the polite refusal the model was
  handed changed nothing, because the next thing the chat did on the same
  connection failed too, naming a table nobody had asked about.

  The tracker tools now open a transaction of their own, the same way scheduled
  triggers do, so a tool that failed costs the tool call and nothing else. The
  visible consequence is the one worth knowing: a tool call that succeeded is
  now kept even if the turn around it does not, so an agent that says it filed
  something has filed it.

- **An issue title too long for the tracker is refused in words.** A model
  writes its own titles and nothing warned it how long one could be, so the
  ordinary way to hit this was for an assistant to be slightly wordy. It now
  hears which field was too long and what the limit is - 200 characters for a
  title, 60 for a label - and shortens it and files the issue, instead of being
  handed a database error about a column it cannot see.

- **One unpublished workflow stopped every scheduled trigger.** A workflow
  somebody was still drawing - an imported one arrives that way - was enough to
  silence the clock for the whole installation: the round that fires the due
  triggers ran in a single transaction, and refusing to start an unpublished
  graph poisoned it, so every trigger in that round had its run and its
  "last fired" rolled back together. A minute later the same triggers were due
  against the same draft, and nothing scheduled ever fired again.

  Each trigger now fires in a transaction of its own, so one that cannot run
  takes only itself down, and a workflow is asked whether it is published rather
  than found out by failing. Nothing to do after upgrading: the triggers that
  stopped resume on the next round.

- **Slack went round the proxy rules entirely.** On a network where outbound
  traffic has to go through a proxy, a rule matching `slack.com` was shown on
  the rules page as the rule that answers and was never once consulted: the
  messages this product posts went out through the SDK's own HTTP client, and
  the Socket Mode websocket dialled out through its own websocket stack. Both
  now go through the rules like every other outbound call, credentials
  included - `chat.postMessage` per call, and the websocket by the rule matching
  the address Slack issued that session, re-asked on every reconnect.

  Worth knowing if you had worked around this: an `HTTPS_PROXY` or
  `http.proxyHost` in the environment used to reach Slack, because its SDK reads
  them. It no longer does, for the same reason nothing else here does - a proxy
  this installation uses should be a rule somebody can see. Write it as one.

  A websocket through a proxy is a `CONNECT` tunnel, so the proxy has to permit
  one to the Slack host. If yours does not, Socket Mode fails where the rule
  pointed rather than silently going somewhere else.

- **The diagnostics page could not say anything on SQLite** - every check
  answered with an error, because one query asked for a table SQLite does not
  have and was the one part of the page not wrapped in a catch. So the easiest
  way to try this product shipped with the screen that exists to say what is
  wrong as the only screen that could not. Each check now runs in its own catch:
  one that cannot answer costs its own card and no other.

- **The stored-secrets check could never fail.** It compared a decryption
  against itself, so it reported everything readable whatever the truth was. It
  now asks the cipher a question the cipher can answer, and names the columns and
  counts when the answer is no. If you restart with a different
  `ORKNUX_SECRET_KEY` than your secrets were written with, this is the screen
  that will tell you.

- **An agent's failures were retried three times by the platform underneath it**,
  including the ones that could only fail the same way - so a refused request
  cost three calls and returned the same message. It is asked once now unless the
  failure is the kind that might come out differently.

- **The foot of the preferences page fell outside what scrolled** when the frame
  was held to the window, taking the last card's clearance with it.

- **Choosing "No one" for an issue's assignee** now clears it.

- **A condition, an action and a webhook can call a plugin's function.** The
  pickers offered them and the save refused them, with a message that said a
  function was needed when one had been chosen.

- **Runs of a workflow you removed can be found, and no longer offer a link into
  an error.** Removing a workflow takes it off the workspace's list and leaves
  every run of it on the executions screen, where two things then went wrong.
  The **Workflow** filter was built from the workflows the workspace still
  lists, so those runs could be scrolled past and never singled out - 76 of 586
  runs on the workspace this was found in. And the workflow name on such a run,
  on the row and on the run's own page, linked to an editor that answered "No
  workflow assignment with id 373", which reads as a broken page rather than as
  a workflow somebody removed.

  The filter is now built from the workflows the runs actually name, with the
  removed ones marked **(removed)** so they are not passed off as live ones, and
  such a run names its workflow without linking to it, saying that it has been
  removed from the workspace. Runs of a workflow that is still there are
  unchanged and still open it.

## 0.8.0

### Added

- **A whole component travels, not only the self-contained ones.** Agents,
  workflows, actions and triggers join the five kinds that could already be
  exported and imported. What a component points at comes with it: an agent
  brings its tools and the skills in the catalogues it was granted, a workflow
  brings everything its nodes reach.
- **An LLM session: a conversation that outlives the run that started it.** An
  agent node can be given a session key, and everything that happens in its turn
  is recorded against it - what was asked, what the agent answered, which tools
  it called and with what, and a note when something went wrong. Two runs that
  compute the same key write into the same conversation, which is the point: a
  ticket seen by two different workflows has one history, not two. Nothing is
  recorded for a node that names no session.

  What was said before goes back in front of the model, so an agent that keeps a
  session remembers it rather than merely writing it down. A session has its own
  page under **AI**, searchable, with the transcript filterable by kind; it can
  be thrown away when it should not have been kept; and it can be **continued in
  chat**, so a person picks up a conversation an agent was having and what they
  say is written into it too.
- **What cannot travel is asked for on arrival.** A model, a connection and an
  MCP server are kept beside a credential, so an envelope names them and
  carries nothing else. The import plan reports each one it cannot satisfy and
  the import asks which of this workspace's rows it means, refusing rather than
  guessing. A row of the same name here binds itself.
- Nothing arrives switched on. A trigger is created disabled, a workflow arrives
  as a draft, and an agent that was granted shell or Orknux access says so in
  the plan before anything is written.
- **An issue can be linked to another** - relates to, blocks, duplicates - and
  the link shows on both ends without the far one being touched. Both histories
  record it, and being blocked is news, because it changes what to do next.
- **A field of an object can say what it means**, and the sentence reaches the
  model as well as the reader: a function whose parameter names an object is now
  handed that object's shape with its descriptions, where before it was told
  only the name and left to guess. Choosing a field's type is a search rather
  than a list, and whether it holds one value or a list is asked separately.
- **A Prometheus endpoint**, at `/actuator/prometheus`, carrying the JVM's own
  measures and three counters worth alerting on: runs started, finished, and
  failed. It answers to anyone signed in; whether it also answers to a scraper
  that has not is a switch on the admin settings page, off until somebody turns
  it on.

### Fixed

- **A page that is still reading says so.** Fifteen settings and editor screens
  drew their whole form while the record they are a form for was still on its
  way - every field blank, every toggle off - so an empty form looked exactly
  like a record with nothing in it, and anything typed into one was overwritten
  when the real values landed. Two of them drew nothing at all below the
  heading. The workflow editor did the same with its canvas, which said the
  workflow was empty and then filled in. Pages that double as create-new forms
  are unchanged: there a blank field is the point.
- **A workspace script that ran out of memory is told that, rather than being
  told it ran too many statements.** The two arrive as the same flag and mean
  opposite things to whoever has to fix the script.
- **An action or a webhook may call a function a plugin declared.** The
  condition catalogue already allowed it; the other two still asked whether the
  workspace owned a function that belongs to no workspace, and refused a choice
  their own picker had just offered. A webhook guarded by one would also have
  refused every caller, since it ran the plugin's source column - which holds a
  note, not code.
- **Interrupting voice mode no longer silences it for the rest of the session.**
  Cutting in while the panel was speaking stopped that sentence and every one
  after it: the microphone went on working and the answers went on arriving,
  but nothing was ever read aloud again, and nothing said so.
- **A shell's account is optional, the way it is at `ssh`.** Leaving it out
  means the account the server runs as, and the Shell page names which account
  that is rather than leaving an administrator to find out by failing.
- **The all-in-one image stops offering single sign-on it does not run.** It
  claimed LDAP, so its sign-in card advertised a directory that was never there
  and its monitoring page reported itself degraded for failing to reach one. It
  now says it signs people in against accounts it holds itself, and the absent
  directory is absent rather than unreachable.
- **A script that threw is not run twice more to watch it throw again.** Every
  script failure was retryable, so a runaway burned its whole budget three times
  over. Failures now say whether asking again could answer differently; only the
  clock and memory can.
- **The script sandbox denies the one thing `HostAccess.NONE` leaves open.** It
  reads as no host access at all, and still permitted mutable target mappings.
  No exploit was found - everything crossing that boundary is a JSON string -
  but the sandbox now matches what its own comments claim.
- **A workspace that cannot be read says why.** Its settings page kept the
  message inside the form, and a load that failed never reached the form, so
  the failure was a heading over blank space.

## 0.7.0

### Added

- **The top bar carries four sections** - AI, Workflow, Workspace and Chat -
  each with its own menu, rather than one Workspace section holding nineteen
  pages. No page changed address; only which menu it sits under. Where a section
  link goes is its own menu's first page, so the two cannot drift apart.

- **Issue news is sent by email as well as shown in the bell**, to anybody whose
  account has an address, with a switch in Preferences for turning it off. It is
  the same audience the bell uses rather than a second set of rules, so changing
  who hears about an issue changes both. An installation with no mail relay
  configured sends nothing and says so only in its log. The subject never
  carries any of the comment, because the subject is what a locked screen shows.

- **Templates: a component published once, for every workspace to take.** A new
  **Templates** page under Admin holds exported components under a name and a
  description, and every catalogue page - Functions, Objects, Conditions, Tools,
  Skills - has a **Use template** button beside Import. A template is nothing
  more than one of those exported files kept by the installation, so using one
  is the import that already exists: it shows what it would create before it
  creates it, renames rather than replaces anything already called the same, and
  refuses outright while it points at a variable the workspace does not have.
  **Publishing is an installation administrator's**, because a template is
  offered to workspaces its author may never see; **using one is anybody who can
  already add a function to that workspace**, which is the point of publishing
  it. Either upload an exported file on the Templates page, or press **Save as
  template** on the component itself - the second is the same file, exported by
  the server, without the trip through a Downloads folder. **A template holds a
  copy taken when it was published and follows nothing**: editing the function
  one was made from does not change it, deleting a template does not touch what
  it has already created, and replacing its file is how one is brought up to
  date. Nothing secret travels, the same as the export it is made of - a
  variable a function is handed appears by name and the workspace it lands in
  supplies its own value. A template written by a newer Orknux than the one
  reading it is still listed, saying so in words, rather than failing when
  somebody presses the button.

- **The tracker can write to you.** Everything the bell already shows - an issue
  you filed, hold or observe being opened, assigned, commented on or closed, and
  any comment with your name in it - is now also posted to your address. It is
  the same news from the same desk rather than a second set of rules about who
  hears what, so nothing changes about the audience: you are still never told
  about your own doing, and a comment that both names you and reaches you as a
  watcher is one message rather than two. The subject says who did what to which
  issue and carries the issue's title, and never a word of what was written -
  that is the part a phone shows on a locked screen. **It sends only where an
  installation has configured `ORKNUX_MAIL_HOST` and `ORKNUX_MAIL_FROM`**, the
  same relay the password reset link goes through, and the default installation
  has none and posts nothing. Somebody with no address on file is passed over
  quietly, which is the ordinary state of an internal account an administrator
  made. **Each person can turn it off** under Preferences → Notifications; it is
  on to begin with, since an installation that has configured a relay has said
  it wants to send mail. The mail is a courtesy on top of the bell and never the
  record of what happened: it is handed over after the save has committed, on a
  thread of its own, and a relay that is down or refusing costs a log line and
  nothing else.

- **A role can now administer one workspace without administering the
  installation.** The workspace settings form has an *Administers* tick beside
  each role it is assigned, and whoever holds a ticked one may change that
  workspace's name and description, put somebody else on one of its issues as an
  observer, and move an issue in or out of it. Only that workspace: the same
  person can lead the support workspace and merely work in the backend one,
  which is the whole reason it is per workspace rather than one blanket
  "workspace admins" role. The name and description are edited from the
  workspace's own Settings page, since an Admin section they cannot reach would
  be no use to them. Nothing installation-wide comes with it - connections,
  proxy rules, shells, users, roles, the installation settings, and creating or
  deleting a workspace all still need an installation administrator, and so does
  the workspace's role list: whoever edits that decides who else gets in and
  could take the role off everybody else, which is a bigger promise than
  changing settings and the one that cannot be walked back. An installation
  administrator administers every workspace without being named on any of them,
  and an installation that upgrades has no workspace administrators anywhere
  until somebody ticks a box.

- **`orknux/orknux-one`: the whole thing in one container.** The interface, the
  server and a SQLite file, published beside the other two images and under the
  same tags. `docker run -p 8080:8080 -v orknux-data:/var/lib/orknux
  orknux/orknux-one` is the whole command: it migrates a fresh database,
  generates the encryption key, creates one administrator and prints the
  password it made up. Everything it is lives in `/var/lib/orknux`, key
  included, so a restart is the same installation and a backup is a copy of one
  directory. **It is not a deployment, and the reasons are on its Docker Hub
  page rather than left to be discovered**: no Temporal, so runs are not durable,
  are not retried and do not resume, and a wait longer than five minutes fails by
  design; no directory and no OIDC; and SQLite, with one writer at a time. The
  manual it ships still describes runs as carried out by Temporal, which is true
  of a deployment and not of that image - it is left correct about the product
  and the difference is written down. CI starts it with nothing supplied, signs
  in as the administrator it invented, writes, restarts it and signs in again,
  because a generated password that does not work looks exactly like an image
  that is fine until somebody tries it.

- **The assistant can read and rewrite a workspace tool.** It could do nothing
  with one before, and the cause was blunt: there was no tool-reading tool at
  all, so on a tool's own address it looked for a *function* with that id, found
  none, and listed the functions. The tool editor gains the wand the function
  editor has, and a proposed change is shown as a diff to accept or reject in
  the same way.

- **A condition is edited on a page of its own**, at `/conditions/new` and
  `/conditions/<id>`, rather than in a modal - the shape a shell, a user and a
  model provider already have. Its fields moved into a form the page and the
  dialog both wear, because that dialog has four callers and duplicating it four
  ways was the alternative. **Open definition** beside the function picker
  reaches the function it calls.

- **An issue has a History tab**, beside the issue itself, holding what has
  happened to it: opened, comments, the status moving, labels going on and
  coming off, it changing hands, and observers arriving and leaving. Every line
  names who did it, oldest first, and changes made through the MCP tools are in
  the same list. Two of these were recorded nowhere before - a label changing
  and an issue changing hands - so they now are, in a table of their own beside
  the workspace audit log rather than instead of it: the audit log answers what
  a workspace has done, this answers why one issue is closed. The tab is fetched
  when it is opened, so an issue nobody presses it on costs what it always did.
  An issue that existed before this release shows the line where recording
  began, with what survived from before it - when it was opened, and everything
  said on it - above that line rather than an empty list implying a quiet week.

- **A node can be duplicated in the workflow editor**, with `Ctrl+D` or the
  button beside Add. The copy carries the definition it points at, its icon and
  facing, and its mappings deep-copied so the two nodes' parameters are
  independent. Its name steps to `X copy`, and so does its output name - that
  one matters, because references resolve by field name, and two nodes both
  offering `reply` means somebody's `reply` silently reads whichever the graph
  lists first. Edges are not copied. The key is rebindable in Preferences beside
  the other five.

- **An edge can be dragged by either end onto another handle.** Reconnecting,
  not bending: a line's shape is not something a workflow can hold, so
  waypoints would either change the saved document or live only in this
  browser, where they would not be an edit at all - not undoable, not visible to
  anybody else. Dropping onto nothing snaps back, and onto wiring that already
  exists is refused as quietly as drawing a duplicate.

- **Open definition opens in the editor's left panel** for triggers, actions and
  conditions, rather than leaving the graph you were editing. Agent and object
  definitions still open their own page: neither has a panel-sized editor to
  put there.

- **The browser tab says what is open** - the workflow, the issue, the agent -
  with the product name last, so a narrow tab strip keeps the half that tells
  two tabs apart. A page whose entity has not arrived says its section rather
  than an id.

- **A link from a function's external parameters to the workspace's variables**,
  which the hint there described without offering. It opens a tab, because this
  editor does not warn before losing unsaved code.

- **An issue being opened is news.** Filing one told the assignee and nobody
  else, so an assistant that filed a finding and named who should know wrote
  into an empty room. Everybody it concerns now hears, except the person it was
  handed to, who is told it is theirs instead.

### Changed

- **The top bar carries the workspace selector, Docs and Admin**, on the right
  beside the account, and the left is the mark and two links. The selector is on
  more screens than it was, not fewer - it used to be missing from docs, chat,
  preferences and the whole admin section, which are exactly the screens
  somebody comes back to a workspace from.

- **Every select is drawn by this application rather than by the operating
  system.** A transparent control is what makes a white menu open over a dark
  page; the pattern most of them already used is now the default, so one written
  tomorrow gets it without remembering. The popup itself is still the browser's
  - it can be tinted and nothing more.

- **The menu's collapse control sits on its first row**, at the column's edge,
  instead of down in the attribution strip where it fell below the fold on a
  laptop screen. Its glyph was two chevrons and is now a panel with one edge
  divided, which says which edge is about to move.

- **A workspace id you cannot see now reads as one that does not exist.** The
  last of what 0.5.0 closed for entities: the create paths, the workspace model
  setters, the graph editor and plugin parameters all answered two ways, so
  walking the numbers counted the workspaces on an installation. Both answers
  are now "no workspace with that id". **The cost is real**: somebody who
  mistypes a workspace id is told it does not exist rather than that it is not
  theirs. An administrator, who can see every workspace, still gets the true
  answer.

- **Voice mode moved into the message composer**, beside the microphone, and is
  drawn as a waveform rather than a speaker. A speaker means "read this aloud";
  voice mode is something you enter.

- **Switching workspace keeps your place where that means anything.** A list
  page stays a list page in the workspace you switched to; a page about one
  particular thing falls back to its list, because issue #4 in another workspace
  is a different issue or none at all.

- **The Orknux logo goes to orknux.ai**, in a new tab. It led nowhere before.

- **Clicking a model provider row opens it**, rather than only the settings
  icon. The models on that page open the same way.

- **A new variable stays where it was added** until the page is opened again,
  rather than sorting itself away from under the cursor. Renaming one does the
  same.

- **The Docker Hub description is published by CI**, from `DOCKERHUB.md`, after
  the images. It was pasted into a web form by hand, which is how it came to be
  eleven variables out of date while being correct in git - and Docker Hub is
  the copy an operator plans an upgrade from. The build now fails if the file
  passes the 25,000 bytes Docker Hub accepts, because the tool that publishes it
  truncates silently.

### Fixed

- **Voice mode releases the microphone on every way out.** It held one open
  stream per entry, so the browser went on reporting the device in use until the
  page was reloaded - three rounds of entering and leaving left three live
  streams and three running audio contexts. The composer's own microphone button
  had a second version of the same fault: it only ever released when somebody
  pressed stop, so navigating away mid-recording left the device open.

- **The chat's composer sits at the bottom of the frame again.** The room every
  page was given at its foot for the floating assistant launcher was room the
  one page whose last element is meant to touch the bottom did not want.

- **The two collapse controls are the same control.** The menu and the catalogue
  panel beside it collapsed by different gestures, with different icons, and the
  catalogue column was 80 pixels shorter than the menu - it cancelled the page's
  padding with one number when that padding is no longer one number.

- **The last button on a page is no longer under the floating assistant
  launcher.** On a settings page that is the delete button in the Danger Zone,
  which overlapped it by 31 by 19 pixels. Padding alone would not have done it:
  the row was pinned to exactly the window height, so a taller page's content
  escaped a box that could not grow. The sidebar's background and the
  attribution strip now reach the bottom of a long page as well.

- **The Admin button is offered only to administrators.** The flag that decides
  defaulted to on, and eleven pages never set it - one of them the docs page,
  which anybody signed in can open.

- **The preferences page could not reach its own end.** 611 pixels of it were
  unreachable at 900 tall, with nothing on the screen able to scroll, so every
  shortcut below Turn Node could not be seen or rebound. The shell's "no
  sidebar" flag also hands a page an overflow it is expected to manage itself,
  and this page did not manage it.

- **The trigger picker's list was clipped to 68 pixels** in a 240 pixel field,
  cutting option names to `Se` and `Sla`. Each picker was still wrapped in a box
  meant for the `select` elements these replaced, and collapsed inside it.

- **Accepting a suggested function change dropped half of it.** The assistant
  wrote the new parameter into the declaration and the accept sent only the
  source, so the save was refused for taking more arguments than the function
  was handed. The parameter list is now read from the code being accepted.

- **A variable created while a page was open is now offered by it**, rather than
  after a reload.

- **The node panel could not be scrolled.** A dialog is `height: fit-content` in
  the browser's own stylesheet, which over-constrained a panel that sets both
  top and bottom, so a webhook trigger's form rendered at 1149 pixels in an 844
  pixel slot with Save unreachable.

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
