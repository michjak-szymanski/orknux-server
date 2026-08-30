# Changelog

What changed in each release, one line per change.

Written for the person deciding whether to upgrade and what to watch for
afterwards - not a list of commits, which git already keeps, and not a list of
issue numbers, which say nothing on their own. A line carries the change and
the one thing worth knowing about it; the reasoning that went into it is in the
commit that made it. Anything that changes what an existing installation does
is under **Changed** and says so in the line itself, because that is the
section people actually need.

One file for both halves of the product: the server and the interface are
released together, under one version, and a reader who has to hold two
changelogs side by side to work out what a release contains is a reader we
have failed.

## Unreleased

### ✨ Added

- 🔌 **A local model server no longer answers every other call with "unexpected
  end of stream".** llama.cpp and most self-hosted servers close an idle
  connection after five seconds; the HTTP client held one for five minutes and
  wrote its next request into a socket the server had already closed. Chats,
  titles and provider checks all failed on it, and the second attempt worked,
  which made it look intermittent. Connections are now dropped before any
  server drops them.

- 🔌 **A provider is asked for its models where it actually keeps them.** The
  check behind "Test Connection" built its own address, and for an Azure
  endpoint written through to `/openai/v1` that came out one path too deep -
  so a provider that answered every message reported that it could not be
  reached, and the card sent you to check the one field that was right. The
  listing goes through the model SDK now, which knows where each of Azure's two
  surfaces keeps it; a server answering in a shape the SDK cannot read is still
  read the old way.

- 🧪 **A model becomes an agent in one press.** Beside every chat model on
  Models there is now a "Make an agent on this model" action: it creates an agent
  on that model, named after it, and takes you to its settings page. The agent is
  granted **nothing** — no tools, no skills, no MCP servers, no catalogues, no
  shell — because granting is a deliberate act; it is a bare agent to dress, and
  it is what makes "I have just added a model, does it work" a short path again.
  Pressing it twice makes a second agent with a number after the name rather than
  failing on the one that is taken.

- 🐙 **GitHub, as a plugin rather than as a connection type.**
  `plugins/github/github.js` guards a webhook trigger with GitHub's HMAC
  signature and names what arrived, so pull requests, review comments and
  pushes start workflows. Load it, accept `TEXT_ENCODING`, point its
  `webhookSecret` at one of the workspace's variables, and add the trigger's URL
  to the repository. Nothing about GitHub is in the server: a host that changes
  its signature scheme is a new version of that file, not a release.

- 💬 **A working task can be told something.** A box on its page takes a message
  while the agent is mid-turn; the agent reads it at the top of its next turn as
  the newest word on what is wanted, and carries on from there — so a report can
  become a table without the task being stopped and started again with a better
  prompt. Until it has been read the page says so, because a turn is minutes.

- 💼 **Microsoft Teams, as a plugin** — `plugins/teams/teams.js`, loaded on the
  Plugins screen. A message in Teams starts a workflow by way of a Teams outgoing
  webhook pointed at a webhook trigger, with the plugin checking the signature
  Teams sends; a workflow answers with an HTTP request action against Graph. No
  new connection type, and nothing to upgrade the server for. Teams has no
  equivalent of Slack's socket, so the receiving half needs this installation to
  be reachable from Microsoft, and the Graph token lives in a workspace variable
  that has to be refreshed about hourly. The README has the setup.

- 🖼 **A task can draw.** Where the workspace has chosen a text-to-image model,
  an agent working a task is offered a tool that draws from a description, and
  every picture it drew is shown under the task's outcome — including on a task
  that ran out of turns before it finished. The picture opens larger when it is
  clicked.

- 🎨 **A chat agent can draw.** Ask for a diagram in the conversation and the
  agent draws one, where the workspace has chosen a text-to-image model. The
  picture is filed as an attachment on the chat and appears in the thread, so it
  is still there when the chat is reopened. A workspace with no image model
  chosen offers no such tool, and no agent is told it could have drawn.

- 🧹 **A task that was never picked up is picked up.** Something now looks every
  few minutes for tasks left sitting at Queued and hands them over again, so a
  hand-over lost to a restart at the wrong moment — or, on Temporal, to a
  workflow that started and could not run — no longer leaves a task nothing will
  ever look at. A task a worker already has is never handed over twice. How long
  a task may sit is a field on Admin → Settings for an installation carrying its
  own tasks, five minutes by default; one running Temporal takes it from
  `ORKNUX_TASK_SWEEP_MINUTES` and is shown no field.

- 🔕 **A provider can be told not to be checked on a timer.** The sweep asks
  every configured provider every few minutes so that "Connected" means today,
  which is right for a provider an installation pays for and wrong for the one
  somebody keeps configured against a box that is only sometimes running — a
  laptop's model server, an endpoint started for an afternoon. There it produced
  a failed row and a page of connection-refused in the log every five minutes
  about a state nobody thought was wrong. **Automatic checks** on the provider's
  own page turns that off; every provider that exists today has it on. It stops
  the timer and nothing else — Test Connection still runs, and so does every
  chat and task the provider serves.

- ⏱ **A task page can be told to catch up on a timer.** The page follows a task
  on a stream, so nothing here waits on a refresh — but a stream can stop
  without saying so, and from the reader's side that looks exactly like a model
  thinking for four minutes. The same interval control the Executions and Audit
  screens carry is now beside the word that says whether the stream is up, Off
  by default and sharing the one setting those screens share. A task that has
  finished is not offered it, having nothing left to change.

### 🔧 Changed

- 🪵 **A provider that cannot be reached is one line in the log rather than a
  page of it.** The stack trace behind a connection refused was printed at WARN
  every time, twice per attempt since the listing tries the SDK and then a
  hand-built request — sixty frames of okhttp and Spring proxies for the
  ordinary answer that a box is switched off. The sentence is kept at WARN and
  the stack moves to DEBUG; what the check found is still written on the
  provider's own row, which is where somebody reads it.

- ⚠️ **A new chat or task can no longer be started on a bare model.** Both
  used to offer a choice between an agent and a model, and it was never a choice:
  a bare model is an agent with the tools, the skills, the grants, the memory and
  the system prompt taken off, and it was sitting beside them as though it were a
  peer. **An existing installation loses the ability to start a chat or a task on
  a bare model.** The Models half of the picker above a chat is gone and so are
  the two calls that moved a chat back onto one; the task form asks for an agent;
  and `startChat` and `startTask` refuse a model on their own. The chats and
  tasks that were started that way are untouched — they open, they render, they
  answer, and a chat can be handed to an agent whenever somebody wants it to be.
  What is new is a workspace with no agent at all: chat and tasks now say so and
  offer the way to add one, where before a bare model quietly filled the gap. A
  workspace nobody had chatted in used to open its first chat on a bare model
  whatever the interface offered; it now opens on the first agent that can answer.

- 🔌 **Custom is no longer a provider type; those providers are OpenAI now.** The
  type named who was at the other end where every other one names what the
  endpoint speaks, so it promised that any wire format would be handled and sent
  an OpenAI request every time — it branched on nothing anywhere in the server.
  **On upgrade** every Custom provider becomes an OpenAI provider with its
  endpoint, its key, its authentication and its models untouched, and the calls
  it makes are the ones it was already making. Google's OpenAI-compatible
  endpoint, a local server or a gateway is an **OpenAI** provider pointed at its
  own address, which is what it was doing under the old name. If you script
  against the API, `CUSTOM` is gone from `ProviderType`.

- 🏁 **A task started on an installation without Temporal now actually runs.**
  Every task started from the Tasks page or from an issue sat at Queued doing
  nothing until the server was restarted: the work was handed to a worker a
  moment before the task itself was written down, so the worker went looking for
  it and found nothing. Nothing is handed over now until the task is there to be
  read — which also covers an approval, an answer or a message, all of which
  reached a task the same way.

- 🖌 **The picture button is gone from the chat composer**, replaced by the
  agent's own drawing tool above. Asking for a picture is now something said in
  the conversation rather than a mode the composer is switched into and the
  description retyped in. Pictures drawn before it went are untouched: they are
  attachments with a line in the thread, and they still show. A chat on a bare
  model rather than an agent can no longer draw at all — a bare model is offered
  no tools.

- 📨 **A webhook run is handed the request's headers**, under `webhook`, beside
  what the body brought — because several senders say which event a delivery is
  in a header and not in the JSON, and a workflow given only the body could not
  tell one apart from another. The body still wins where both name a field, and
  the headers HTTP has names for carrying a credential are left out of the row,
  so nothing changes for a workflow already written against a webhook.

- ⏱ **A shell command is no longer stopped after a minute, and its output no
  longer loses the end.** The command timeout ships at ten minutes and the kept
  output at 256 KiB, and both can be set per machine on Admin → Shell, where
  leaving a box empty means the installation's own default. Output over the
  allowance keeps **both ends** now rather than the first bytes only, with a line
  between them saying how much went — so a build that fails after a long
  download shows the error rather than the download. An installation that was
  relying on the old minute will find commands running longer before they are
  stopped.

- 🔒 **A credential on a shell command line is no longer audited in the clear.**
  A password in a git remote, a `curl -u`, an `Authorization` header, a
  `--token=` or an exported `…_TOKEN` is replaced by `***` before the row is
  written — rows written before this are left as they are, and any credential
  already in them should be treated as disclosed.

- 🔒 **A credential in a tool call is no longer kept in the session
  transcript.** What an agent passed a tool is redacted the same way an audit
  line is, so a password in a `git push` URL is `***` on the chat and task
  pages and in the table behind them; what a tool *returned* is stripped only of
  the things that are a credential on sight — GitHub, Slack, AWS and similar
  tokens, and private keys — because replacing every `password` and `--token` in
  a build log would cost the agent the output it works from. A secret in command
  output with no recognisable shape is still stored, and rows written before
  this are left as they are.

- 🔒 **A chat watched live now shows the same redacted lookup a reload does.**
  The stored copy of a tool call was stripped of its credentials and the frames
  streamed to the chat window were not, so one `git push` read `alice:***@host`
  after a reload and `alice:s3cr3t@host` while it was running; both are now the
  one redacted string, under the same two strengths, and the agent goes on being
  handed the command as it was written.

- 💬 **What an agent says on its way to a lookup is kept.** A model may answer
  with a message and tool calls in the same reply — "let me read the skill
  first" — and that message was thrown away: off the task and chat pages, and
  gone from the agent's own memory by its next turn, so a task whose progress
  was reported that way lost the only copy of it. It is now written into the
  session above the calls it came with, under the agent's name. A round that
  said nothing writes nothing, which is nearly all of them.

- 🔌 **An Anthropic model no longer refuses a turn that put two of a role
  together.** That API takes its messages strictly alternating, and a round that
  called several tools threaded a result back per message — so the request was
  refused outright and the agent got a provider error rather than an answer.
  Consecutive turns are now joined into one message, as separate parts, when the
  Anthropic request is built; every other provider is sent exactly what it was
  sent before.

- 🧠 **A task's thinking closes when the model starts answering**, rather than
  staying open until the turn ends — a long answer no longer leaves the block
  counting up with the reasoning stopped mid-sentence.

- 🛑 **Interrupting a chat now stops the model, rather than only stopping
  listening to it.** Pressing the big circle in voice mode put the panel back to
  Listening and left the request exactly where it was: the model went on writing
  an answer nobody would ever hear, every word of it was charged for, and the
  next thing said raced a turn that had never ended. The interruption now aborts
  the request, and the server hangs up on the provider when the reader goes —
  which it could not previously notice at all while the model was thinking,
  because nothing was being written to find out on. The composer has a **Stop**
  beside the send button that does the same thing for a typed turn. An answer
  that was stopped is not written to the history: the chat keeps the question and
  no answer, rather than half a sentence attributed to the model.

## 0.9.4

### ✨ Added

- 🤖 **Tasks: an agent given a problem, working at it until it is done.** Its own
  section, its own page, and a log that fills in as the model works — what it is
  thinking, what it called, what came back.
- 🚀 **Start by AI on an issue**, which hands the issue's own agent the title, the
  kind, the labels, the description and the thread, picks the issue up, and says
  so in the thread with a link to the task.
- ❓ **A task can stop and ask** — a question, or permission for a capability —
  and waits for an answer rather than guessing.
- 🎛 **A workspace decides how many turns a task may take.** Empty means the
  installation's own number.
- 🏷 **Issue types, decided per workspace**, rather than a fixed list.
- 💬 **Slack triggers on a message and on a reply to one of our own bots** —
  a reply is matched by the author of the thread it hangs under.
- 📦 **A library can be installed from npm**, fetched once into the database and
  served from there; CommonJS packages included.
- ▶ **A function can be test-run from its editor**, down the path a workflow runs
  it on, with a field per parameter — and the workspace's variables can be given
  by hand for the run.
- 🔍 **Where a component is used, asked in one place** — every function, tool,
  skill, agent, action and condition says what depends on it before you remove it.
- 🎨 **A fourth kind of model, one that draws**, and a picture button in the chat.
- 🇵🇱 **Polish**, chosen per person — including what the server refuses and why.
- 💰 **What a chat has spent**, as a running total kept on the chat itself.
- 🧠 **What the model thought before it answered**, in a chat and on a task,
  arriving while it is being thought rather than after.
- 🗑 **A comment can be taken off an issue**, and off everywhere it was copied to.
- 📄 **An action is edited on a page of its own**, the way a condition is.
- 🔔 **A bell that rings for a task**, and news about one.
- 🧭 **The top bar remembers where you have been.**

### 🔧 Changed

- 📡 **A Slack trigger belongs to the app, not to one connection row.** One app
  opens one socket per row and Slack delivers each event to exactly one of them,
  so a trigger bound to a single row fired on a fraction of what it was set up
  for. Every row of that app now hears it — *including rows in other workspaces*,
  each still answering for its own token and its own scopes.
- 🛡 **One trigger failing no longer stops the others** that were waiting on the
  same event.
- 🔑 **A bot token that gains a scope is noticed at once**, rather than after ten
  minutes of the old answer.
- 🧰 **A function's wiring is a page of its own**, off the editor's column.
- ⌨ **A Slack connection trigger needs the event subscribed in the Slack app**,
  not only the scope granted — the manual and the Action picker now say which
  events and where.

### 🐛 Fixed

- 📜 **A chat opens on its newest turn** rather than at the top of the thread.
- ⏳ **A task's turn streams.** It was one blocking call per round, so nothing
  reached the page between one turn and the next.
- 🧵 **A page joining mid-thought is told the rest of it**, instead of watching a
  line that never finishes.
- 👯 **A line on a task's page is drawn once**, not twice.
- 🏁 **A task's stream drains before it says it has ended**, so nothing is lost
  at the last frame.
- ⚙ **A task's worker is given the activity its workflow calls** — Tasks did not
  run at all without it.
- 📝 **Switching workspace while filing an issue keeps you on the form**, rather
  than dropping you on the next workspace's issue list.
- 🔇 **A reply trigger says once that something reached it** and was not what it
  asked for, so silence can be told from nothing arriving.
- 🖼 **The Test Run window no longer flashes its own explanation** as it opens.

## 0.9.3

### ✨ Added

- 🔓 **Authentication can be turned off**, `ORKNUX_AUTH_METHOD=NONE`, for an
  installation already behind a gate of its own — every request then administers
  it, and the interface says so on every page.
- ☸ **Orknux runs on Kubernetes from a manifest kept in this repository.**
  `deploy/kubernetes/orknux.yaml` is `deploy/compose.yaml`'s five services
  written as Kubernetes objects, with a README for what differs.
- 🔑 **Every stored credential can reference a workspace secret**, per field, so
  a connection can hold one token and reference another.
- 🧩 **A function or a tool can call another function**, declared in the editor
  and resolved by the host rather than by the sandbox.
- 📚 **Libraries: JavaScript an installation loads once and its scripts import**,
  administered centrally and refusing removal while something depends on it.
- 🔒 **A plugin says which JavaScript it needs, and loading it asks you to
  agree.** Five named builtins; there is no name for a file, a socket or a Java
  class, so none can be asked for.
- ⏻ **A trigger can be switched on and off from the two screens that define
  one**, not only from the list.
- 🎙 **Voice mode keeps listening while it is thinking and while it is talking**,
  and holds what you said until the turn comes round.
- ⌨ **You can type as well as speak in voice mode**, and Send queues rather than
  drops while the model is busy.
- 🔊 **Where an answer is cut for the speech model is a workspace setting** —
  None, Sentence or Paragraph, on the Voice card.
- 🔁 **A chat's last answer can be asked for again**, and the one it replaces is
  kept as a take you can step back to.
- 📏 **A model's context window can be set** on the model's own page, which is
  the screen the refusal about it already named.

### 🔧 Changed

- 🗣 **An answer read aloud is the answer as it is drawn**, not the markdown
  behind it; a code block is announced rather than read.
- ⏩ **Reading an answer aloud starts on its first sentences** instead of waiting
  for the whole answer to be synthesised.
- 🔒 **A plugin no longer gets `console` or `Intl` for free.** GraalJS turns both
  on by default; a plugin that uses them must now declare them and be re-loaded.
- 🤖 **The picker above a chat offers agents before models.**
- 💬 **Switching workspace while in a chat leaves you in the chat**, rather than
  sending you to the Flow section.

- ✂ **The Create Trigger dialog dropped the sentence explaining what a trigger
  is**, above a form whose first field is called Trigger Name.
- 🏷 **A trigger of the connection kind is called Connection**, not Incoming
  Connection; there is no outgoing one to tell it apart from.
- 🔗 **The node panel opens a definition by the same mark every other form
  uses**, rather than by the words "Open definition".

### 🐛 Fixed

- 🔑 **A function reached through `imports` is handed its own workspace
  variables.** It read them as `undefined` - a wrong answer rather than a
  failure - while the editor promised the sandbox would supply them.
- ⏳ **The editor knows `imports.f(...)` gives back a promise.** It was annotated
  as the bare return type, so a call without `await` type-checked and then
  handed back a promise at run time.
- 📚 **A library whose export is a class instance lists what it offers.** Only
  its own fields were read, so a bundle keeping its API on a prototype showed
  its internals and none of its methods. Running one was never affected.
- 🕸 **A run's graph drawn as boxes with nothing between them.** Every rebuild
  threw away the handle positions every line is drawn from.
- 🎙 **Voice mode no longer says it is speaking before anything has been
  spoken**; the caption follows the audio.
- ⏳ **A conversation held by voice says the model is working**, in the
  transcript and not only in the voice panel.
- 🔗 **An agent's settings point at everything they name** — the model, the
  catalogs, the tools and the MCP servers, in both frames.
- 💾 **The trigger settings page can be saved more than once per visit.** Save
  stayed disabled after the first press until the page was reloaded.

## 0.9.2

### ✨ Added

- 🎚 **A workspace decides when voice mode has heard enough.** Three settings on
  a Voice card: how long a pause ends your turn, how far above the room's own
  noise a sound must stand, and how long an open microphone stays open.
- 🔑 **A model provider's key can be a reference to a workspace secret.**
  **Value** or **Reference** beside the field itself, held by id, so renaming
  the variable or moving it disturbs nothing.
- 💬 **A Slack action's target can be picked from what the connection can see,
  and is judged when it is typed.** Users and channels filtered as you type, in
  both places a target is set, and still free text.
- ⌨ **Publishing a workflow has a keystroke.** Ctrl+Enter, rebindable in
  Preferences, refusing exactly where the button refuses.

### 🐛 Fixed

- 🎙 **Voice mode stops when you have finished, not when you go quiet.** The
  threshold is measured from the room now, the 1.2-second pause is 2.5, and the
  thirty-second cap is ten minutes.
- 🏷 **A Slack action's target no longer asks which kind it is.** Nothing read it
  when sending - and removing it turned up that `@alice` reached nobody, before
  this change as much as after.
- 🦙 **A provider of type Ollama is spoken to in Ollama's dialect.** It was asked
  for `/models`, which Ollama does not serve, so a correct address answered *"No
  model list - check the endpoint"*.
- 📇 **Somebody who signs in through the directory is written down again.** The
  event that puts an external user on the Users page was never published for the
  directory door.
- 🗄 **Monitoring names the database the installation actually stores in.** It
  said Postgres to everybody, including every `orknux-one`, which stores in
  SQLite.
- 📝 **A new issue is written on a blank form.** Opening Create issue from an
  issue carried that issue's title, description and labels into it.
- ✍ **Switching workspace no longer throws away a half-written issue.** It still
  leaves, but it asks, and offers to file the issue where it was written.
- 🕸 **A run's graph is drawn even when its size arrives a moment late.** Nodes
  declare their size now, rather than being drawn invisible until something took
  a fresh measurement.
- 🧾 **An audit feed written in one moment comes back in one order.** Both audit
  queries sorted on the timestamp alone.

## 0.9.1

### ✨ Added

- 🧠 **A workspace sets what its agents default to.** The same memory share
  slider on an **Agents** card, used by every agent that sets none of its own.
- ⏰ **A cron expression says what it does, under the field it is typed in.** It
  follows what is typed rather than what was saved, and one that can never come
  round says so in those words.
- 🧠 **How much conversation an agent carries is now a setting, and it is one
  number.** A **Session Memory** share of the model's context window replaces
  five constants in the source; an agent that sets nothing carries what it did.
- 📖 **A picture in the manual opens at the size it was taken.** Clicking one
  opens it over the page, with Escape, click-away and the focus kept inside.
- 🔍 **"Go to" offers things to do, as well as everywhere to go.** Create issue,
  function and condition, marked with a plus, in a box now called **Quick
  actions**.
- 🕸 **The lines in a workflow point where the run goes.** An edge a run travels
  ends in an arrowhead at the target, red on the failure branch; the dashed
  dependency lines deliberately keep none.
- 🤖 **An agent can put a name on an issue.** `orknux_update_issue` takes an
  `assignee` by the name somebody would say, `"nobody"` hands it back, and a
  name matching nobody is refused rather than ignored.

### 🔧 Changed

- 🧰 **Removing an MCP server now takes the grant off the agents that held it.**
  It used to leave the name behind, so registering that name again handed every
  agent still holding the grant whatever now answered at the new address.

### 🐛 Fixed

- 💬 **The text in the chat composer sits in the middle of its box.** The row
  aligned its contents to the floor, which put the whole difference between a
  line of text and a 32px button above the text and none of it below.
- 🕸 **The control in a node's corner wears a rotate arrow, and no longer crowds
  the node.** It was the two-arrow glyph that means refresh everywhere else, and
  it sat inside the corner among the resize handles.
- 🧪 **CI runs both databases now, side by side.** The build tested one engine,
  and the untested one is what `orknux-one` ships with; both legs run in parallel
  and both must pass before an image is built.
- 💬 **An installation with chat turned off stops offering a chat's settings.**
  The workspace's Chat card did not honour the switch; the Quick Chat model stays,
  in a card of its own.
- 📖 **A screenshot in the manual is no longer dimmed in the light theme.** The
  rule that darkens icon files was catching every picture in the documentation.
- 🗄 **An agent's tools work in a chat on SQLite.** Every tool call came back as
  `Unable to commit against JDBC Connection`; the chat now writes what was said,
  asks the model outside any transaction of its own, and writes the answer after.
- 🧰 **Renaming an MCP server carries the grants with it.** They are held by
  name, so a rename left every agent holding a grant that matched nothing and was
  dropped in silence.
- ⏰ **A cron of seconds is now a schedule this actually keeps.** The tick ran
  once a minute; it now runs on `ORKNUX_SCHEDULER_TICK_INTERVAL` and starts every
  occurrence that came due, with catching up bounded to a minute.
- ⏰ **A schedule that can never come round is refused when it is saved.**
  `0 0 30 2 *` is well-formed, saved, and was skipped on every tick for ever.
- ⌨ **Shift + Enter grows the message box, and it grows all the way to the top.**
  The two-hundred-pixel cap is measured rather than chosen now: the composer
  takes what the conversation above it can spare.
- 💬 **The send button no longer says "Sending" once the message has been sent.**
  It reads *Waiting…* and then *Answering…*, off the same condition the
  conversation's own row uses.
- 💬 **The chat selector is no longer cropped, and links to what it names.** A
  `max-width` beat its `width`, so a panel meant to be 560 pixels wide drew its
  tabs outside a ninety-pixel box; it opens leftwards now.
- 🤖 **The status that says somebody has started is no longer hidden from the
  agents.** `orknux_set_issue_status` accepted `IN_PROGRESS` all along and
  described itself as taking two values.
- 🧾 **An issue somebody picked up is no longer audited as reopened.** Both doors
  wrote two answers to a question with three; the wording lives beside the enum
  now, decided once for all three.
- 🧠 **An agent no longer answers a later question out of its own summary of a
  lookup.** A call and what it returned are one line of the session now, with a
  budget of their own, and a chat with an agent keeps a session to hold them.
- 📝 **The issue tools no longer answer about the first two hundred issues as
  though it were the tracker.** `orknux_issue_labels` counted a page rather than
  the tracker, and `labels` and `assignee` filtered after fetching one.

## 0.9.0

### ✨ Added

- 🔁 **A node's retry policy is a policy, not a checkbox.** Attempts and a single
  wait with a "double it" tick have become attempts, an initial wait, a
  multiplier, a ceiling, jitter and a total budget; a graph you already have does
  exactly what it did.
- 🌐 **An HTTP action's headers are built as rows, and a value may name a
  variable.** They were a JSON blob typed by hand, so a bearer token was pasted
  in as a literal, stored unencrypted, into a field that is not a credential
  field.
- 🎨 **Three times as many icons to label a node with**, 94 to 291, chosen by
  what the nodes in this product actually reach - tickets, source control,
  infrastructure, storage, security, money, documents, logistics.
- 📋 **The workflow list can be sorted, and shows as many rows as you ask for.**
  Name, last run or switched on, in either direction, ordered by the server, with
  the order kept in the address.
- 🤖 **An agent's definition opens beside the graph** rather than instead of it,
  the way every other kind already did.
- 💾 **The four editors ask before unsaved work is walked away from.** Function,
  tool, object and skill, and only where there is a change to lose.
- 🧾 **Creating and closing an issue is written to the audit log** whichever door
  it came through; the tools an agent uses recorded nothing.
- 🛟 **A node can be told what to do when it fails.** A retry policy and a second
  way out, drawn as a red **If fails** line beside the green one - and every
  attempt is another billed call.
- 🔍 **The chat shows the lookups an agent made**, not only the words it said.
  What the model is sent is unchanged.
- 💬 **A turn carried into a chat says who said it**, which matters once a
  session holds both an agent's turns and a person's.
- 🕸 **Turning a node is offered on the node**, above the selected one beside its
  resize handles.
- 🔗 **The object a function parameter names is one link away**, beside the
  selector.
- 🕰 **Functions, tools, skills and agents keep what they were.** Every save keeps
  the version it replaced, with a **History** panel that puts one back; nothing
  from before the upgrade is recovered.
- ♻ **A workflow's publications are kept, and one can be put back into service.**
  They used to be one row per workflow, overwritten on every Publish; restoring
  leaves the draft alone, and variables are deliberately not versioned.
- ⏳ **How long that history is kept is an Admin setting**, fourteen days by
  default (`ORKNUX_REVISION_RETENTION_DAYS`).
- 🔑 **A Slack connection's app-level token can be read back.** It could be typed
  and never seen again.

### 🔧 Changed

- 🔌 **Google AI is no longer a provider type; use Custom.** It never worked;
  stored providers become Custom with their endpoint and key untouched, and
  `GOOGLE_AI` is gone from `ProviderType`.
- 🧭 **The Ollama endpoint hint points at `/v1`.** The form suggested
  `http://localhost:11434`, and anyone who took the hint at its word got a 404.
- 🧩 **There is one Slack connection type, not two.** Outgoing only and Socket
  Mode were one integration under two names; connections carry across, and
  `SLACK_SOCKET_MODE` is gone from `ConnectionType`.
- 🛑 **Deleting an issue asks first.** It used to delete on the click that
  reached it.
- ⏱ **The loader waits three seconds before it appears**, everywhere it is used.
  It was five, which no screen ever reached.
- ✏ **A line in the workflow editor takes as many bend points as you put on it.**
  Double-click the line to add one, double-click a point or press Delete to take
  it off; arrangements already made are read and written unchanged.
- 🚫 **Deleting something that is still in use is refused, and says what is using
  it.** Actions, agents, conditions, triggers, tools, skill catalogs and memory
  catalogs; objects are the deliberate exception.
- 🏷 **Toggling a tool's or a skill's Active badge no longer discards the draft.**
  It applied the whole stored copy over the form.
- 🪝 **"Webhook" no longer names two opposite things.** A trigger of that kind is
  incoming; the outgoing connection kind is now **HTTP**, with existing
  connections carried across.
- 🧹 **Jira and GitHub are no longer offered as connection kinds.** Nothing
  implemented either; both become HTTP connections, which is what they were.
- 👁 **A secret is revealed by the same control everywhere.** Every one is now the
  eye, it toggles, and it says which state it is in.
- 📖 **The readmes name the other three addresses** the site answers to.

### 🐛 Fixed

- 🔍 **The magnifier above a conversation searches the conversation.** It searched
  the list of chat titles, which is the sidebar's question.
- 🔢 **The footer says which version of Orknux this is.** It named the product,
  the copyright holder, the licence and the source, and not the one thing anybody
  is asked for first.
- 🗑 **Deleting a chat asks first.** The trash button removed the chat and every
  message in it on one press, with nothing said.
- 🔦 **The chat's search button searches.** It used to focus a hidden read-only
  input that existed only to be focused.
- 🖱 **The whole message box takes a click.** Only the narrow line of text did, so
  a press on the padding went nowhere.
- 📐 **The chat's title bar is one row.** The model or agent answering sat in a
  bar of its own below the title, carrying one word.
- 👆 **Every icon control in the product answers the pointer**, not only the ones
  on the four pages this was first noticed on.
- 📋 **The Add menu in the workflow editor keeps the two LLM kinds together.** Its
  order came from the order somebody had declared the labels in.
- 💡 **The small square at the end of a row lights up under the pointer, and does
  it the same way on every page.** It had been declared sixteen times in sixteen
  stylesheets, four ways.
- ❓ **A dialog explains itself behind the (?) beside its title**, rather than in
  a paragraph above the form.
- ⌨ **Typing quickly in a code editor no longer scrambles what you typed.** The
  editor wrote stale text back into the document a keystroke later, and what was
  saved was the scrambled text.
- 💾 **An editor no longer asks about unsaved work on a function nobody touched.**
  The guard fired by counting renders and the rewrite happened on a later one; it
  is asked by value now.
- 🔤 **The workflow list's first column no longer says "Template name"** on a list
  of workflows.
- ✏ **A line in the workflow editor accepts extra bend points even when it
  carries a label.** The label sat on the first point as its only handle,
  covering the part of the line you would aim at.
- 〰 **The line from a session node is drawn as a dependency**, dashed, like every
  other line that says "this uses that" rather than "this runs next".
- ✖ **The editor's side panel can be closed from its top-right corner.** The only
  way out was at the bottom, past the whole form.
- 🗄 **A workspace can be deleted on SQLite, which is what the one-container image
  runs.** Any workspace holding an action that calls a function could not be
  deleted at all; Postgres was never affected.
- 🖌 **Twenty-seven backgrounds across the interface were painted with a colour
  that does not exist.** They were transparent, showing whatever happened to be
  behind them.
- 📡 **A list that could not be fetched no longer says the workspace is empty.**
  Pickers and grant lists turned a failed request into "there are none yet".
- 🖼 **Pictures sent to an Anthropic model now arrive.** They did not: a turn
  carrying a screenshot reached the model as words alone, and one that genuinely
  cannot be carried now fails naming the format.
- 🚢 **The one-container image works on whatever port you publish it on.** nginx
  forwarded the Host header with the port removed, so every ordinary call was
  treated as cross-origin and refused.
- 🛡 **The proxy rules now cover the calls that were going round them.** A host
  was resolved here before the rules were consulted, and OIDC sign-in and mail
  went round them too; LDAP still cannot be routed, and the page says so.
- 🧵 **A chat turn no longer disappears because a tool call failed.** The tracker
  tools ran inside the chat's transaction; they open one of their own now, so a
  tool call that succeeded is kept even if the turn is not.
- ✂ **An issue title too long for the tracker is refused in words.** A model now
  hears which field was too long and what the limit is - 200 characters for a
  title, 60 for a label.
- ⏰ **One unpublished workflow stopped every scheduled trigger.** The round that
  fires due triggers ran in one transaction, so a draft rolled back every
  trigger's run and its "last fired"; each fires in its own now.
- 🔒 **Slack went round the proxy rules entirely.** Its SDK's own HTTP client and
  websocket stack dialled out directly; an `HTTPS_PROXY` in the environment no
  longer reaches Slack, so write it as a rule.
- 🩺 **The diagnostics page could not say anything on SQLite** - every check
  answered with an error, because one query asked for a table SQLite does not
  have and was the one part not wrapped in a catch.
- 🩺 **The stored-secrets check could never fail.** It compared a decryption
  against itself, so it reported everything readable whatever the truth was.
- ⛔ **An agent's failures were retried three times by the platform underneath
  it**, including the ones that could only fail the same way.
- 📐 **The foot of the preferences page fell outside what scrolled** when the
  frame was held to the window, taking the last card's clearance with it.
- 👤 **Choosing "No one" for an issue's assignee** now clears it.
- 🧰 **A condition, an action and a webhook can call a plugin's function.** The
  pickers offered them and the save refused them.
- 📂 **Runs of a workflow you removed can be found, and no longer offer a link
  into an error.** The Workflow filter is built from the workflows the runs name,
  with removed ones marked **(removed)**.

## 0.8.0

### ✨ Added

- 📤 **A whole component travels, not only the self-contained ones.** Agents,
  workflows, actions and triggers join the five kinds that could already be
  exported and imported, and what a component points at comes with it.
- 🧠 **An LLM session: a conversation that outlives the run that started it.** An
  agent node given a session key records its turn against it, two runs computing
  the same key share one history, and a session can be continued in chat.
- 🔑 **What cannot travel is asked for on arrival.** A model, a connection and an
  MCP server sit beside a credential, so the import plan reports each one it
  cannot satisfy and asks which row is meant rather than guessing.
- 🔌 **Nothing arrives switched on.** A trigger is created disabled, a workflow
  arrives as a draft, and an agent granted shell or Orknux access says so in the
  plan before anything is written.
- 🔗 **An issue can be linked to another** - relates to, blocks, duplicates -
  showing on both ends, recorded in both histories, and being blocked is news.
- 🗂 **A field of an object can say what it means**, and the sentence reaches the
  model as well as the reader.
- 📊 **A Prometheus endpoint**, at `/actuator/prometheus`, carrying the JVM's own
  measures and three counters worth alerting on: runs started, finished, failed.

### 🐛 Fixed

- 📐 **A page that is still reading says so.** Fifteen settings and editor screens
  drew a whole blank form while the record was still on its way, and anything
  typed into one was overwritten when the real values landed.
- 📜 **A workspace script that ran out of memory is told that, rather than being
  told it ran too many statements.** The two arrive as the same flag and mean
  opposite things to whoever has to fix the script.
- 🧰 **An action or a webhook may call a function a plugin declared.** Both
  refused a choice their own picker had just offered.
- 🎙 **Interrupting voice mode no longer silences it for the rest of the
  session.** Cutting in stopped that sentence and every one after it, with
  nothing said.
- 🖥 **A shell's account is optional, the way it is at `ssh`.** Leaving it out
  means the account the server runs as, and the Shell page names which.
- 🚢 **The all-in-one image stops offering single sign-on it does not run.** It
  claimed LDAP, so it advertised a directory that was never there and reported
  itself degraded for failing to reach one.
- 📜 **A script that threw is not run twice more to watch it throw again.** Every
  script failure was retryable, so a runaway burned its whole budget three times
  over.
- 🔒 **The script sandbox denies the one thing `HostAccess.NONE` leaves open.** It
  still permitted mutable target mappings; no exploit was found.
- 📡 **A workspace that cannot be read says why.** Its settings page kept the
  message inside a form that a failed load never reached.

## 0.7.0

### ✨ Added

- 🧭 **The top bar carries four sections** - AI, Workflow, Workspace and Chat -
  each with its own menu, rather than one Workspace section holding nineteen
  pages. No page changed address.
- 📧 **Issue news is sent by email as well as shown in the bell**, to anybody
  whose account has an address, with a switch in Preferences for turning it off.
- 📤 **Templates: a component published once, for every workspace to take.** An
  Admin page holds exported components and every catalogue page gains **Use
  template**; publishing is an administrator's, using one is anybody's, and a
  template holds a copy and follows nothing.
- 📧 **The tracker can write to you.** The same news from the same desk, sending
  only where `ORKNUX_MAIL_HOST` and `ORKNUX_MAIL_FROM` are set, with the subject
  naming who did what to which issue and never a word of what was written.
- 🔐 **A role can now administer one workspace without administering the
  installation.** An *Administers* tick beside a role on the workspace's settings
  form; nothing installation-wide comes with it.
- 🚢 **`orknux/orknux-one`: the whole thing in one container.** The interface, the
  server and a SQLite file under `/var/lib/orknux`; not a deployment, and its
  Docker Hub page says why rather than leaving it to be discovered.
- 🤖 **The assistant can read and rewrite a workspace tool.** There was no
  tool-reading tool at all, so on a tool's address it looked for a function with
  that id and listed the functions.
- ✏ **A condition is edited on a page of its own**, at `/conditions/new` and
  `/conditions/<id>`, rather than in a modal.
- 📝 **An issue has a History tab**, holding what has happened to it with changes
  made through the MCP tools in the same list; a label changing and an issue
  changing hands were recorded nowhere before.
- 🕸 **A node can be duplicated in the workflow editor**, with `Ctrl+D`, carrying
  its definition, icon and facing and a deep copy of its mappings; edges are not
  copied.
- 🕸 **An edge can be dragged by either end onto another handle.** Reconnecting,
  not bending - dropping onto nothing snaps back, and onto existing wiring is
  refused.
- 🕸 **Open definition opens in the editor's left panel** for triggers, actions
  and conditions, rather than leaving the graph you were editing.
- 🧭 **The browser tab says what is open** - the workflow, the issue, the agent -
  with the product name last.
- 🔗 **A link from a function's external parameters to the workspace's
  variables**, which the hint there described without offering.
- 🔔 **An issue being opened is news.** Filing one told the assignee and nobody
  else, so a finding filed with nobody named wrote into an empty room.

### 🔧 Changed

- 🧭 **The top bar carries the workspace selector, Docs and Admin**, on the right
  beside the account; the selector is on more screens than it was, not fewer.
- 🎨 **Every select is drawn by this application rather than by the operating
  system.** A transparent control is what makes a white menu open over a dark
  page.
- 📐 **The menu's collapse control sits on its first row**, at the column's edge,
  instead of down in the attribution strip below the fold on a laptop screen.
- 🔒 **A workspace id you cannot see now reads as one that does not exist.** The
  last of what 0.5.0 closed; the cost is real, since a mistyped id now reads as
  absent rather than as somebody else's.
- 🎙 **Voice mode moved into the message composer**, beside the microphone, drawn
  as a waveform rather than a speaker.
- 🧭 **Switching workspace keeps your place where that means anything.** A list
  page stays a list page; a page about one particular thing falls back to its
  list.
- 🔗 **The Orknux logo goes to orknux.ai**, in a new tab. It led nowhere before.
- 🖱 **Clicking a model provider row opens it**, rather than only the settings
  icon; the models on that page open the same way.
- 🗂 **A new variable stays where it was added** until the page is opened again,
  rather than sorting itself away from under the cursor.
- 📖 **The Docker Hub description is published by CI**, from `DOCKERHUB.md`, and
  the build fails if the file passes the 25,000 bytes Docker Hub accepts.

### 🐛 Fixed

- 🎙 **Voice mode releases the microphone on every way out.** It held one open
  stream per entry, and the composer's own button only ever released on stop.
- 💬 **The chat's composer sits at the bottom of the frame again.** The room every
  page was given for the floating assistant launcher was room the one page whose
  last element touches the bottom did not want.
- 📐 **The two collapse controls are the same control.** They collapsed by
  different gestures, with different icons, and the catalogue column was 80
  pixels shorter than the menu.
- 📐 **The last button on a page is no longer under the floating assistant
  launcher.** On a settings page that is the Danger Zone's delete button, which
  it overlapped by 31 by 19 pixels.
- 🔐 **The Admin button is offered only to administrators.** The flag that decides
  defaulted to on, and eleven pages never set it.
- 📐 **The preferences page could not reach its own end.** 611 pixels of it were
  unreachable at 900 tall, so every shortcut below Turn Node could not be seen or
  rebound.
- 🕸 **The trigger picker's list was clipped to 68 pixels** in a 240 pixel field,
  cutting option names to `Se` and `Sla`.
- 🤖 **Accepting a suggested function change dropped half of it.** The accept sent
  only the source, so a new parameter in the declaration was lost and the save
  was refused; the parameter list is read from the code being accepted now.
- 🗂 **A variable created while a page was open is now offered by it**, rather
  than after a reload.
- 🕸 **The node panel could not be scrolled.** A dialog is `height: fit-content`
  in the browser's own stylesheet, which over-constrained a panel that sets both
  top and bottom.

## 0.6.0

### ✨ Added

- 🗄 **Orknux runs on SQLite**, as well as Postgres, decided by `ORKNUX_DB_URL`
  and nothing else - no second container and a backup that is one file. Postgres
  is still what a deployment should use, and the README lists what differs.
- 🖥 **An agent can run commands on a machine.** An Admin → Shell page holds SSH
  targets; a granted agent opens a session, gets a working directory of its own
  and loses it on close, and what contains this is the machine rather than
  anything in the application.
- 🛡 **One address can be reached through a proxy without sending everything
  through it.** An Admin → Networking page holds ordered rules matched against
  the request URL, covering every outbound call except mail.
- 🔐 **An installation can be entered without a directory.**
  `ORKNUX_BOOTSTRAP_ADMIN_USERNAME` and `ORKNUX_BOOTSTRAP_ADMIN_PASSWORD` create
  one internal administrator at startup; it only ever creates, and a password in
  a variable is a way in to be undone rather than a credential to keep.

### 🔧 Changed

- 🗄 **The migrations moved into a directory per database**, `db/migration/postgresql`
  with the SQLite baseline alongside. Nothing changes for an existing
  installation; it matters to anyone writing one, since a schema change is now
  written twice.
- 🔒 **An id that is not yours reads as one that is not real, whether you read it
  or change it.** Sixty-five mutations now throw exactly what the absent case
  threw, so a client that treated the refusal as "ask an administrator" will see
  "not found" instead.

### 🐛 Fixed

- 🔒 **The Entra ID token endpoint is checked before the client secret is posted
  to it.** It was the last outbound call not asked where it was going, and it is
  a POST carrying the application's client secret.

## 0.5.0

### ✨ Added

- 📝 **An issue can be moved to another workspace**, administrators only, taking
  its comments, labels, links, observers and files but not its number - so the
  old address stops working, and the move is written into the issue, both
  workspaces' activity and everybody following it.
- 🔔 **Observers on an issue**, below its labels: anybody in a workspace can watch
  one in a press, an administrator can put somebody else on the list, and a model
  cannot observe because it has nowhere to read its news.
- 🤖 **`orknux_open_issue` takes observers**, so an assistant can put a finding in
  front of somebody without assigning them the work.
- 📧 **Forgotten passwords can be reset by mail.** A link that works once, stops
  after an hour and signs the account out everywhere; internal accounts only, and
  it needs `ORKNUX_MAIL_HOST`, `ORKNUX_MAIL_FROM` and `ORKNUX_BASE_URL`.
- 🔗 **Links can be added while an issue is being written**, rather than only
  after it exists.
- 📋 **Sorting a list of issues by last comment**, which is not the same as by
  last change: closing, relabelling and assigning all move the change time.
- 👤 **An address on a user**, taken from the directory or the OIDC provider at
  sign-in and refreshed from there until somebody types their own.
- ▶ **A run says which run it was started from**, for both kinds of re-run, and
  links back to it.
- ▶ **Run, in the workflow editor**, which starts the graph in front of you and
  takes you to the run it made; it uses the draft, deliberately.

### 🔧 Changed

- ▶ **A workflow switched off now stays off.** Nothing that starts a run ever read
  the switch, so on upgrade a workflow left off some time ago and quietly running
  anyway will stop the moment this is installed.
- 🔐 **An OIDC bearer token is now checked against who it was issued for, and this
  one can lock people out.** Only the issuer was checked; a token must now name
  this installation in `aud`, or `ORKNUX_OIDC_AUDIENCES` must say what the tokens
  actually carry.

### 🐛 Fixed

- 🔔 **Waiting for tracker news no longer costs a thread.** `orknux_news` sat on
  the request thread for up to five minutes, so a couple of hundred such calls
  took the server off the air; `ORKNUX_ASYNC_REQUEST_TIMEOUT` bounds how long a
  request answered this way may stay open.
- 🔒 **A webhook body has a size limit.** An anonymous caller chose the size of
  five copies of it; `ORKNUX_WEBHOOK_MAX_BODY_SIZE` defaults to `1MB` and refuses
  anything larger with 413 before it is read.
- 🔒 **Signing in can no longer be tried without limit.** Wrong passwords are
  counted per username and per address, with a pause that doubles and then stops;
  nothing locks, and a successful sign-in clears the count.
- 📎 **The files sent into a chat are as private as the chat.** Attachments were
  checked against the workspace, so anybody who could see it could list and
  download somebody else's.
- 🔒 **A refusal no longer names the workspace it is protecting.** "You do not
  have access to workspace "frontend"" answered a question nobody may ask, and
  GraphQL reports errors with a 200, so trying every id was a script.
- 💬 **The `@` mention list appeared at the bottom of the whole editor box**
  rather than at the mention, which in the comment box put it off the window with
  only two names reachable.
- 📋 **The sort control's options did not name the field they sorted on** -
  "Newest" sorted by number and read as a date, so a correct order looked wrong
  against the times beside it.

## 0.4.0

### ✨ Added

- 🔗 **Links on an issue.** An address gets a row of its own rather than a
  sentence in the description, a GitHub one shown as `owner/repo#123` worked out
  from the address alone; only `http` and `https` are kept.
- ▶ **A run can be started again from one of its steps**, rather than only from
  the beginning: the steps ahead appear as what they were, marked carried over,
  and it refuses where that cannot honestly be done.
- 📦 **A compose file that brings up a whole Orknux**, at `deploy/compose.yaml` -
  the published images, the database, a directory and Temporal on one published
  port.

### 🐛 Fixed

- 📝 **An issue in a list read "opened by alice - 18 minutes ago" beside the time
  it last changed**, so down a correctly ordered list the times ran in no order
  at all. It shows when the issue was opened.
- 🔒 **Spring Boot 4.0.7**, which closes an unauthenticated LDAP bind on exactly
  the mechanism this product signs people in with, and a Tomcat request smuggling
  hole across the boundary people authenticate over.
- 📎 **The volume example for the server image mounted a path the image does not
  contain**, so it was created owned by root and no attachment could be written;
  move the volume to `/home/orknux` and set `ORKNUX_ATTACHMENTS_LOCATION`.

## 0.3.0

### ✨ Added

- 📝 **An issue tracker in every workspace.** Open, In progress and Closed, one
  search across titles, descriptions and labels, filters that live in the
  address, and issues carrying comments, `@` mentions and pasted screenshots.
- 🔔 **A notification bell**, beside the account menu, reporting issues you filed
  changing state, comments on issues that concern you, and your name written in
  one, across every workspace you can see.
- 📧 **Mail.** An SMTP connection holds the server details, its password
  encrypted like every other credential, and a Send Email action takes a
  recipient, subject, body, cc and reply-to.
- 🧰 **The tracker over MCP**: an assistant can list, read, open, comment on,
  label and close issues, and wait on a feed of what has happened.
- 🔐 **Internal users with passwords, and `orkx_` access tokens** for reaching the
  API and the MCP endpoint as a named person.
- 🕸 **A workflow editor that can be undone.** Undo and redo, rebindable
  keystrokes, pickers you type into, components created beside the canvas rather
  than over it, and nodes that can be turned.
- 🎙 **Voice mode says what it is doing** - listening, thinking or speaking - and
  can be interrupted mid-answer.

### 🔧 Changed

- ▶ **Publishing means something now, and this is the one to read twice.** A
  trigger, a schedule or the API runs the published copy; a workflow that was
  never published, and was running because nothing checked, will stop - publish
  it once.
- ▶ **Re-running a run repeats the graph that ran**, rather than whatever is being
  edited now.
- 🔗 **A link into the interface no longer opens in a new tab**; a modified click
  still does, because these are real links now.

### 🐛 Fixed

- 🔐 **Deleting a role a workspace depended on removed it silently**, taking
  access with it. It is refused, and names the workspaces in the way.
- 🔐 **The admin settings page was offered to anybody signed in.** Administrators
  only, as every other admin page already was.
- ✏ **"No errors", "Formatting valid" and "Schema compile healthy" were what those
  editors opened with**, before anything had been checked, and survived every edit
  after; they start as "not checked yet" now.
- 📎 **An oversized upload, or one to an installation with attachments switched
  off, answered 500 and "Internal Server Error".** Both now say what happened.
- 🕸 **The workflow editor's mapping labels could not be dragged** - the node
  beneath took the press - and a label that did move left its line behind.
- 🤖 **An agent node's output was named `reply` by a placeholder and by nothing
  else**, so the node declared nothing and nothing downstream could point at it.
- 📐 **Long pages grew instead of scrolling**, pushing the attribution bar off the
  bottom.
- 📋 **Sorting a list of issues by title failed outright**, because the query
  joined the labels and Postgres will not order a distinct select by an
  expression outside its select list.

## 0.2.0 and earlier

Not written down. The changelog starts here, which is the honest place to start
it: reconstructing releases from their commits afterwards produces something
that reads like a changelog and is nobody's account of what happened.
