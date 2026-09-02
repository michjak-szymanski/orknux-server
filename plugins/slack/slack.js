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
 * 3. Use `slack_isFirstReply` as a function condition, or `slack_thread` from
 *    anywhere a function is called.
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
    return ['SLACK_READ_THREAD'];
  }

  functions() {
    return [
      new OrknuxFunction({
        name: 'thread',
        description: 'The messages in a Slack thread, oldest first, with the reply count.',
        params: [
          { name: 'connection', type: 'map' },
          { name: 'channel', type: 'string' },
          { name: 'threadTs', type: 'string' },
        ],
        returnType: 'map',
        run: (connection, channel, threadTs) =>
          orknux.slack.thread(connection ?? this.settings.slack, channel, threadTs),
      }),

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
    ];
  }
}
