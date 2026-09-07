package io.mszymanski.orknux.workflow.script

/**
 * The JavaScript both sandboxes hand their guests, written once.
 *
 * A plugin and a workflow function reach the server through the same door and
 * get the same helpers, but the helpers used to be written out twice - once in
 * [ScriptRunner] and once in [PluginRunner] - and the copies had already drifted:
 * when functions were given HTTP on 2026-09-06 the plugin side kept an older
 * shape with no `post` and no parsed `json`, so the same call written in the two
 * places answered differently. Two copies of an API is two APIs.
 *
 * What differs between them is one sentence - what to say when the door is not
 * there - and that is a parameter rather than a reason to write it all again.
 */
internal object HostHelpers {

    /**
     * The levels, as the guest compares them, and what a setting names.
     *
     * `off` is a number nothing reaches rather than a branch of its own: a
     * threshold is one comparison wherever it is written, and a special case
     * would be a second way for this to be wrong.
     */
    val LEVELS = mapOf("debug" to 10, "info" to 20, "warn" to 30, "error" to 40, "off" to 99)

    /** The number a named level compares as, or `info` for a name nobody has. */
    fun threshold(named: String): Int = LEVELS[named.trim().lowercase()] ?: LEVELS.getValue("info")

    /**
     * `orknux.log`, as both a plugin and a function see it.
     *
     * A sandbox with no `console` is a sandbox nobody can debug. `print` and
     * `load` are turned off by name and are staying off - they write to the
     * server's own streams and take whatever they are given - so this is the way
     * to say something: the line is built in the guest, crosses as text, and the
     * server decides where it goes.
     *
     * **The level is decided in here, before anything crosses.** A `debug` line
     * on an installation logging at `info` costs one comparison and is dropped
     * where it was written, so a function may leave its tracing in and pay for
     * it only when somebody turns the level down.
     *
     * Arguments are joined with a space, and anything that is not a string is
     * JSON - which is what somebody logging an object wants, and the alternative
     * is `[object Object]`.
     *
     * @param threshold the lowest level that is kept, as its number.
     */
    fun log(threshold: Int): String = """
        log: (function () {
          const levels = { debug: 10, info: 20, warn: 30, error: 40 };
          const kept = $threshold;

          function say(level, args) {
            if (levels[level] < kept) return;
            const line = Array.prototype.map
              .call(args, function (one) {
                if (typeof one === 'string') return one;
                try {
                  return JSON.stringify(one);
                } catch (ignored) {
                  // A cycle, or something JSON cannot hold. Saying what it was
                  // beats dropping the line somebody wrote to find a problem.
                  return String(one);
                }
              })
              .join(' ');
            globalThis.__orknuxLog(level, line);
          }

          return {
            debug: function () { say('debug', arguments); },
            info: function () { say('info', arguments); },
            warn: function () { say('warn', arguments); },
            error: function () { say('error', arguments); },
          };
        })(),
    """.trimIndent()

    /**
     * `orknux.slack`, as both a plugin and a function see it.
     *
     * Read a thread, post a message, add a reaction - each hands the host a
     * connection and gets an answer back as data, the connection read out of an
     * object or taken as a bare id so `trigger.connection` and a number both
     * work. Written here once for the reason http and log are: the two runners
     * had their own copy of the thread reader and were one edit from disagreeing.
     *
     * Each door says its own thing when it is not wired, because the reason
     * differs: a plugin was not granted the capability, a function is on an
     * installation that answers none. Both are data, like every refusal here.
     */
    fun slack(readAbsent: String, postAbsent: String, reactAbsent: String): String = """
        slack: {
          thread(connection, channel, threadTs, limit) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.slack_read_thread === undefined) {
              return { error: '$readAbsent' };
            }
            const id = connection === null || typeof connection !== 'object' ? connection : connection.id;
            return JSON.parse(host.slack_read_thread(JSON.stringify([id, channel, threadTs, limit ?? null])));
          },
          post(connection, channel, text, threadTs) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.slack_post_message === undefined) {
              return { error: '$postAbsent' };
            }
            const id = connection === null || typeof connection !== 'object' ? connection : connection.id;
            return JSON.parse(host.slack_post_message(JSON.stringify([id, channel, text, threadTs ?? null])));
          },
          react(connection, channel, ts, emoji) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.slack_add_reaction === undefined) {
              return { error: '$reactAbsent' };
            }
            const id = connection === null || typeof connection !== 'object' ? connection : connection.id;
            return JSON.parse(host.slack_add_reaction(JSON.stringify([id, channel, ts, emoji])));
          },
        },
    """.trimIndent()

    /**
     * `orknux.http`, as both a plugin and a function see it.
     *
     * The guest never holds a socket: it hands over a URL and gets an answer
     * back as data, which is what keeps this a door rather than a network.
     * Where it may get to is the installation's proxy rules - the same rules a
     * Slack call and an MCP call obey - which this cannot see and cannot argue
     * with.
     *
     * Answers `{ status, headers, body }` with `json` beside `body` where the
     * reply parsed, or `{ error }` saying why not. A refusal is data, like every
     * other answer here: a condition that cannot reach a service has to be able
     * to decide rather than throw and become undecidable.
     *
     * @param absent what to say when this installation has no door wired.
     */
    fun http(absent: String): String = """
        http: {
          request(what) {
            const host = globalThis.__orknuxHost;
            if (host === undefined || host.network_request === undefined) {
              return { error: '$absent' };
            }
            const asked = what === null || typeof what !== 'object' ? { url: what } : what;

            const headers = Object.assign({}, asked.headers ?? {});
            let body = asked.body ?? null;
            /*
             * An object body is JSON, and says so.
             *
             * Stringifying it by hand is the easy half; the header is the half
             * people forget, and a service answering 415 to a body that looks
             * perfectly good is a bad afternoon. A string body is passed through
             * untouched - somebody sending form-encoded text meant it.
             */
            if (body !== null && typeof body === 'object') {
              body = JSON.stringify(body);
              const named = Object.keys(headers).some((name) => name.toLowerCase() === 'content-type');
              if (!named) headers['content-type'] = 'application/json';
            }

            const answer = JSON.parse(
              host.network_request(
                JSON.stringify([
                  asked.url ?? null,
                  (asked.method ?? 'GET').toUpperCase(),
                  headers,
                  body,
                ]),
              ),
            );

            /*
             * `json` beside `body`, never instead of it.
             *
             * Nearly every service answers JSON and nearly every caller wants it
             * parsed, so parsing it here saves the same three lines being written
             * every time - and a reply that is not JSON, or is JSON the service
             * got wrong, simply has no `json` rather than throwing. `body` is
             * always the text that arrived, so nothing is hidden by this.
             */
            if (answer.error === undefined && typeof answer.body === 'string') {
              try {
                answer.json = JSON.parse(answer.body);
              } catch (ignored) {
                // Not JSON. `body` still is what it is.
              }
            }
            return answer;
          },

          /** The two nearly everybody wants, spelled out. */
          get(url, headers) {
            return globalThis.orknux.http.request({ url, method: 'GET', headers });
          },

          post(url, body, headers) {
            return globalThis.orknux.http.request({ url, method: 'POST', body, headers });
          },
        },
    """.trimIndent()
}

/**
 * Which of this installation's things a script call belongs to, for the log.
 *
 * A line saying `isFirstSlackMessage: no thread` is the beginning of a question
 * rather than an answer: on a server running many workflows at once the next
 * thing anybody asks is *which run*, and matching on the clock is what this
 * exists to save. Everything here is optional because not every caller has it —
 * a function being test-run from the editor belongs to no execution, and a
 * webhook's authenticator to no workflow — and what is absent is left out
 * rather than printed as a question mark, so a line carries only what is true.
 */
data class ScriptOrigin(
    val functionId: Long? = null,
    val workflowId: Long? = null,
    val executionId: Long? = null,
) {

    /** `[workspace 9 function 448 execution 2682]`, with the workspace the run's. */
    fun said(workspace: Long?): String {
        val parts = buildList {
            workspace?.let { add("workspace $it") }
            functionId?.let { add("function $it") }
            workflowId?.let { add("workflow $it") }
            executionId?.let { add("execution $it") }
        }
        return if (parts.isEmpty()) "[script]" else parts.joinToString(" ", "[", "]")
    }
}
