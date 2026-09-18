/*
 * GitHub, as a plugin.
 *
 * GitHub is not a connection type and there is no GitHub trigger. What this
 * installation already had was a webhook trigger that answers on a path, checks
 * an arriving body against a shape, and asks a function whether the caller may
 * start anything. All this does is be that function, and know GitHub's three
 * payloads well enough to say what one of them is about.
 *
 * The whole integration is therefore a file somebody loads and a workspace
 * points a trigger at. Nothing about GitHub is in the server, which is the point
 * of it being here: a repository host that changes its signature scheme is a new
 * version of this file, not a release.
 *
 * ## Setting one up
 *
 * 1. Load this plugin (Plugins, Load a plugin) and accept TEXT_ENCODING.
 * 2. Put the webhook secret in one of the workspace's variables, and point the
 *    plugin's `webhookSecret` parameter at it. It is declared as a secret, so a
 *    typed-in value is refused — a variable is the only answer it takes.
 * 3. Make an object with `repository` and `sender` on it, which is what every
 *    payload below has in common and is what the trigger checks an arriving body
 *    against.
 * 4. Make a webhook trigger on that object, authenticating with the function
 *    `github_verify`.
 * 5. In the repository's settings, add a webhook pointing at the trigger's URL,
 *    content type `application/json`, with that same secret, sending pull
 *    requests, pull request review comments and pushes.
 *
 * The run is handed the body, and `webhook.headers` beside it — which is where
 * GitHub says which event this is. `github_describe` turns the pair into one
 * flat answer a condition or an action can read.
 *
 * ## Why the hashing is written out longhand
 *
 * A plugin's sandbox has no crypto: it hands out language builtins and nothing
 * else, on purpose, and there is no permission that could be asked for that
 * would open a door to the host. So SHA-256 and HMAC are here, in the plugin,
 * which is exactly what "a plugin declares the JavaScript it needs" means. It
 * costs roughly a thousand statements per 64 bytes hashed, so a payload in the
 * hundreds of kilobytes will run out of the sandbox's statement budget and the
 * caller will be refused — with the reason written into the trigger's log. Set
 * the repository's webhook to send the events below rather than everything, and
 * nothing it sends comes close.
 *
 * Written as JavaScript rather than as TypeScript compiled to it: the server
 * runs JavaScript, and a plugin somebody may need to load in a hurry should not
 * need a build first.
 */

/** The round constants of SHA-256, as FIPS 180-4 gives them. */
const ROUND_CONSTANTS = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

/** The initial hash value of SHA-256. */
const INITIAL = new Uint32Array([
  0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
]);

/** The block size HMAC pads its key out to. */
const BLOCK = 64;

function rotate(word, by) {
  return ((word >>> by) | (word << (32 - by))) >>> 0;
}

/**
 * SHA-256 of some bytes, as bytes.
 *
 * Straight out of the specification, working on a Uint8Array so nothing here
 * depends on how a string was encoded — that decision is made once, above.
 */
function sha256(bytes) {
  const hash = INITIAL.slice();

  // One 0x80 byte, then zeroes, then the length in bits as 64 big-endian bits:
  // so the message needs at least nine bytes of room past its own end.
  const padded = new Uint8Array(((((bytes.length + 8) / BLOCK) | 0) + 1) * BLOCK);
  padded.set(bytes);
  padded[bytes.length] = 0x80;

  const view = new DataView(padded.buffer);
  const bits = bytes.length * 8;
  view.setUint32(padded.length - 8, Math.floor(bits / 0x100000000));
  view.setUint32(padded.length - 4, bits >>> 0);

  const schedule = new Uint32Array(64);
  for (let at = 0; at < padded.length; at += BLOCK) {
    for (let index = 0; index < 16; index++) {
      schedule[index] = view.getUint32(at + index * 4);
    }
    for (let index = 16; index < 64; index++) {
      const early = schedule[index - 15];
      const late = schedule[index - 2];
      const mixEarly = rotate(early, 7) ^ rotate(early, 18) ^ (early >>> 3);
      const mixLate = rotate(late, 17) ^ rotate(late, 19) ^ (late >>> 10);
      schedule[index] = (schedule[index - 16] + mixEarly + schedule[index - 7] + mixLate) >>> 0;
    }

    let a = hash[0];
    let b = hash[1];
    let c = hash[2];
    let d = hash[3];
    let e = hash[4];
    let f = hash[5];
    let g = hash[6];
    let h = hash[7];

    for (let round = 0; round < 64; round++) {
      const sum1 = rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25);
      const choose = (e & f) ^ (~e & g);
      const first = (h + sum1 + choose + ROUND_CONSTANTS[round] + schedule[round]) >>> 0;
      const sum0 = rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22);
      const majority = (a & b) ^ (a & c) ^ (b & c);
      const second = (sum0 + majority) >>> 0;

      h = g;
      g = f;
      f = e;
      e = (d + first) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (first + second) >>> 0;
    }

    hash[0] = (hash[0] + a) >>> 0;
    hash[1] = (hash[1] + b) >>> 0;
    hash[2] = (hash[2] + c) >>> 0;
    hash[3] = (hash[3] + d) >>> 0;
    hash[4] = (hash[4] + e) >>> 0;
    hash[5] = (hash[5] + f) >>> 0;
    hash[6] = (hash[6] + g) >>> 0;
    hash[7] = (hash[7] + h) >>> 0;
  }

  const digest = new Uint8Array(32);
  const out = new DataView(digest.buffer);
  for (let word = 0; word < 8; word++) {
    out.setUint32(word * 4, hash[word]);
  }
  return digest;
}

/** HMAC-SHA-256, as RFC 2104 gives it. */
function hmacSha256(key, message) {
  const shortened = key.length > BLOCK ? sha256(key) : key;
  const padded = new Uint8Array(BLOCK);
  padded.set(shortened);

  const inner = new Uint8Array(BLOCK + message.length);
  const outer = new Uint8Array(BLOCK + 32);
  for (let at = 0; at < BLOCK; at++) {
    inner[at] = padded[at] ^ 0x36;
    outer[at] = padded[at] ^ 0x5c;
  }
  inner.set(message, BLOCK);
  outer.set(sha256(inner), BLOCK);
  return sha256(outer);
}

function hex(bytes) {
  let written = '';
  for (let at = 0; at < bytes.length; at++) {
    written += (bytes[at] < 0x10 ? '0' : '') + bytes[at].toString(16);
  }
  return written;
}

/**
 * Whether two hex digests are the same, in time that does not depend on where
 * they first differ.
 *
 * A comparison that returns early tells whoever is guessing how much of their
 * guess was right, one byte at a time, which is enough to forge a signature
 * without ever knowing the secret.
 */
function sameDigest(mine, theirs) {
  if (typeof theirs !== 'string' || mine.length !== theirs.length) {
    return false;
  }
  let differing = 0;
  for (let at = 0; at < mine.length; at++) {
    differing |= mine.charCodeAt(at) ^ theirs.charCodeAt(at);
  }
  return differing === 0;
}

/** A header by name, from a map whose keys the server has already lower-cased. */
function header(headers, name) {
  if (headers === null || typeof headers !== 'object') {
    return null;
  }
  const held = headers[name];
  return typeof held === 'string' ? held : null;
}

/** A nested field, or null rather than a thrown error on the way down. */
function at(holder, name) {
  if (holder === null || typeof holder !== 'object') {
    return null;
  }
  const held = holder[name];
  return held === undefined ? null : held;
}

export default class Github extends OrknuxPlugin {

  id() {
    return 'github';
  }

  apiVersion() {
    return 1;
  }

  parameters() {
    return [
      new OrknuxParameter({
        name: 'webhookSecret',
        description: 'The secret set on the repository\'s webhook, which every delivery is signed with.',
        type: 'string',
        required: true,
        // The webhook's own secret. Declared as a secret so it cannot be typed
        // into the plugins page: the only way to answer it is to point at one of
        // the workspace's variables, which is where this installation encrypts
        // what it keeps.
        secret: true,
      }),
    ];
  }

  permissions() {
    // TextEncoder, and nothing else. GitHub signs the bytes it sent, so the
    // body has to become bytes the same way it was written — UTF-8 — rather
    // than by whatever a hand-rolled loop happens to do with a character
    // outside the ASCII range. A pull request title with an accent in it is
    // not an edge case.
    return ['TEXT_ENCODING'];
  }

  functions() {
    return [
      new OrknuxFunction({
        name: 'verify',
        description: 'Whether a delivery really came from GitHub, by its HMAC signature.',
        params: [
          { name: 'headers', type: 'map' },
          { name: 'rawBody', type: 'string' },
        ],
        returnType: 'boolean',

        /*
         * The bytes GitHub signed, not the JSON they parse to.
         *
         * `rawBody` is the request exactly as it arrived, which is what the
         * signature is over. Re-serialising the parsed body would reorder keys
         * and drop whitespace, and the digest of that is a digest of something
         * GitHub never sent.
         */
        run: (headers, rawBody) => {
          const secret = this.settings.webhookSecret;
          if (typeof secret !== 'string' || secret.length === 0) {
            // Required, so a workspace that has not set it is already marked as
            // needing to. Answering no is the safe reading of not knowing.
            return false;
          }
          if (typeof rawBody !== 'string') {
            return false;
          }

          const sent = header(headers, 'x-hub-signature-256');
          if (sent === null || sent.indexOf('sha256=') !== 0) {
            // Unsigned, or signed with the SHA-1 scheme GitHub still sends in
            // `X-Hub-Signature` for compatibility. Neither is a delivery this
            // will vouch for: accepting the old header would let a caller
            // choose the weaker of the two.
            return false;
          }

          const encoder = new TextEncoder();
          const mine = hex(hmacSha256(encoder.encode(secret), encoder.encode(rawBody)));
          return sameDigest(mine, sent.slice('sha256='.length));
        },
      }),

      new OrknuxFunction({
        name: 'describe',
        description: 'What a delivery is about: its event, who did it, where, and to what.',
        params: [
          { name: 'headers', type: 'map' },
          { name: 'body', type: 'map' },
        ],
        returnType: 'map',

        /*
         * One answer for the three events the webhook is set up to send, so a
         * condition can ask `kind === 'push'` rather than working out which of
         * GitHub's shapes arrived from which fields happen to be present.
         *
         * `event` is a header rather than a body field, which is why the run is
         * handed `webhook.headers` at all. Anything else GitHub might send is
         * described as far as it has anything in common with these — `other`,
         * with the repository and the actor — rather than refused, because a
         * repository whose webhook was set to send everything should not make
         * this throw.
         */
        run: (headers, body) => {
          const event = header(headers, 'x-github-event');
          const repository = at(body, 'repository');
          const pull = at(body, 'pull_request');
          const comment = at(body, 'comment');

          const described = {
            event: event,
            delivery: header(headers, 'x-github-delivery'),
            kind: 'other',
            action: at(body, 'action'),
            repository: at(repository, 'full_name'),
            actor: at(at(body, 'sender'), 'login'),
            title: null,
            url: null,
            number: null,
            ref: null,
            commits: null,
          };

          if (event === 'pull_request') {
            described.kind = 'pull_request';
            described.number = at(body, 'number');
            described.title = at(pull, 'title');
            described.url = at(pull, 'html_url');
          } else if (event === 'pull_request_review_comment') {
            described.kind = 'review_comment';
            described.number = at(pull, 'number');
            described.title = at(comment, 'body');
            described.url = at(comment, 'html_url');
          } else if (event === 'push') {
            described.kind = 'push';
            described.ref = at(body, 'ref');
            const pushed = at(body, 'commits');
            described.commits = Array.isArray(pushed) ? pushed.length : 0;
            described.title = at(at(body, 'head_commit'), 'message');
            described.url = at(body, 'compare');
          }

          return described;
        },
      }),
    ];
  }
}
