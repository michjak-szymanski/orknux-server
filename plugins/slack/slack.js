/*
 * Slack, as a plugin.
 *
 * What this exists for is the question a workflow cannot answer from the payload
 * alone: what is in this thread. Slack's `message` event carries `thread_ts` and
 * `parent_user_id` and no count, so "is this the first reply" — which is the
 * commonest thing anybody wants to gate a workflow on — is unanswerable from
 * what arrives. Reading the thread is the only way, and reading it needs the
 * network.
 *
 * A plugin has no network, deliberately and permanently: the sandbox is built
 * with `IOAccess.NONE`, and `PluginPermission` is a closed list with no spelling
 * for a socket. So this asks the *server* to read the thread, under a capability
 * a person accepted, through a connection a workspace pointed it at. The plugin
 * never sees a token and could not use one.
 *
 * ## Setting one up
 *
 * 1. Load this plugin and accept `SLACK_READ_THREAD`.
 * 2. Point its `slack` parameter at the workspace's Slack connection.
 * 3. Use `slack_isFirstReply` as a function condition.
 *
 * Nothing here wraps `orknux.slack.thread` for the sake of it. That call is the
 * API and is available to any plugin granted the capability; a function that
 * only forwarded its arguments to it would be a name to look up in exchange for
 * nothing. What is here is what the call does not answer on its own - plus the
 * three lookups below, whose wrapping IS the point: an agent has no code and
 * calls functions by name, so `readMessage`, `whoIs` and `mention` exist to be
 * granted to agents as tools. Their descriptions are written for the model
 * that reads them.
 *
 * ## Why the connection is an argument and not just a setting
 *
 * A workspace with two Slack connections has two Slacks. A reply that arrived on
 * one has to be read through that one — read through the other it is a thread
 * that does not exist, or worse, a different thread with the same timestamp. So
 * every function here takes a connection, and a trigger says which one its event
 * came in on: wire `trigger.connection` to the condition's argument and it is
 * right by construction rather than by whichever connection was configured.
 *
 * Falls back to the `slack` parameter when nothing is passed, which is what a
 * workspace with one Slack wants and is one fewer thing to wire.
 */

export default class Slack extends OrknuxPlugin {

  id() {
    return 'slack';
  }

  apiVersion() {
    return 1;
  }

  parameters() {
    return [
      new OrknuxParameter({
        name: 'slack',
        description:
          'The Slack to read through when a function is not handed one. ' +
          'A workspace with two Slacks should pass the connection instead.',
        type: 'connection',
        connectionType: 'SLACK',
        required: false,
      }),
    ];
  }

  permissions() {
    return [];
  }

  capabilities() {
    return ['SLACK_READ_THREAD', 'SLACK_READ_MESSAGE', 'SLACK_READ_USER', 'SLACK_MENTION'];
  }

  functions() {
    return [
      new OrknuxFunction({
        name: 'isFirstReply',
        description:
          'Whether this message is the first reply in its thread. False for a message that is not in a thread at all.',
        /*
         * `ts` as well as the thread, because the two together are the whole
         * question. A message whose own timestamp *is* the thread's is the
         * parent, not a reply - Slack gives a message outside a thread a
         * `threadTs` of its own `ts`, so without this every top-level message
         * would look like a first reply.
         */
        params: [
          { name: 'connection', type: 'map' },
          { name: 'channel', type: 'string' },
          { name: 'threadTs', type: 'string' },
          { name: 'ts', type: 'string' },
        ],
        returnType: 'boolean',
        run: (connection, channel, threadTs, ts) => {
          if (typeof threadTs !== 'string' || threadTs === '' || threadTs === ts) {
            return false;
          }

          const read = orknux.slack.thread(connection ?? this.settings.slack, channel, threadTs, 2);
          if (read.error !== undefined) {
            /*
             * Thrown rather than answered false. A condition that cannot be
             * decided must not quietly decide: "we could not read the thread"
             * and "this is not the first reply" are different facts, and a
             * workflow that treated them alike would silently stop firing the
             * day a scope was revoked.
             */
            throw new Error(`could not read the thread: ${read.error}`);
          }

          /*
           * Slack's own count, which is of the whole thread rather than of what
           * came back. One reply, and this is it.
           */
          return read.replies === 1;
        },
      }),

      new OrknuxFunction({
        name: 'readMessage',
        description:
          'Reads the Slack message a permalink points at. Use when a message links to another message ' +
          '(https://…slack.com/archives/…) and you need what that message says. Pass the connection the ' +
          'event came in on, or an empty string to use the configured one. Answers channel, ts, user and text.',
        params: [
          { name: 'connection', type: 'string' },
          { name: 'link', type: 'string' },
        ],
        returnType: 'map',
        run: (connection, link) => {
          const read = orknux.slack.message(connection || this.settings.slack, link);
          if (read.error !== undefined) {
            throw new Error(`could not read the linked message: ${read.error}`);
          }
          return read;
        },
      }),

      new OrknuxFunction({
        name: 'whoIs',
        description:
          'Says who a Slack user id belongs to. Use when a message carries a mention like <@U0123ABCD> ' +
          'and you need the person behind it; pass the id bare or as the whole <@…> notation. Pass the ' +
          'connection the event came in on, or an empty string to use the configured one. Answers id, ' +
          'name, realName, displayName and whether it is a bot.',
        params: [
          { name: 'connection', type: 'string' },
          { name: 'userId', type: 'string' },
        ],
        returnType: 'map',
        run: (connection, userId) => {
          const found = orknux.slack.user(connection || this.settings.slack, userId);
          if (found.error !== undefined) {
            throw new Error(`could not look the user up: ${found.error}`);
          }
          return found;
        },
      }),

      new OrknuxFunction({
        name: 'mention',
        description:
          'Turns a name into the notation Slack renders as a mention: <@U…> for a person, <!subteam^S…> ' +
          'for a user group. Use it to ping somebody in a message you are composing - put the answer in ' +
          'the message text as it is, and never write <@…> from a guessed id. Takes a display name, ' +
          'username, email, id or group handle. Pass the connection the event came in on, or an empty ' +
          'string to use the configured one.',
        params: [
          { name: 'connection', type: 'string' },
          { name: 'name', type: 'string' },
        ],
        returnType: 'string',
        run: (connection, name) => {
          const resolved = orknux.slack.mention(connection || this.settings.slack, name);
          if (resolved.error !== undefined) {
            throw new Error(`could not resolve the mention: ${resolved.error}`);
          }
          return resolved.mention;
        },
      }),
    ];
  }
}
