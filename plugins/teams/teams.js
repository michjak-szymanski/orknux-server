/*
 * Microsoft Teams, as a plugin.
 *
 * Teams has no Socket Mode. Slack's websocket is what lets an installation
 * behind a firewall hear about a message without publishing a port, and there
 * is nothing on the Teams side that does the same job: the two ways Microsoft
 * delivers a message are an Azure Bot messaging endpoint and a Graph change
 * notification, and both of them are Microsoft calling a URL of ours. So the
 * receiving half here is a Teams **outgoing webhook** - the one delivery Teams
 * offers that needs no Azure application, no Graph consent and no subscription
 * to renew - pointed at one of this installation's webhook triggers.
 *
 * What makes that safe to point at the open internet is the header Teams sends
 * with it. Every request carries `Authorization: HMAC <signature>`, where the
 * signature is HMAC-SHA256 of the exact bytes of the body, keyed by the security
 * token Teams handed out when the webhook was created. Checking that is pure
 * arithmetic over the request, which is precisely what a plugin can do and what
 * a webhook trigger's `FUNCTION` authentication was built to ask - so `verify`
 * below is the gatekeeper, and the token reaches it as a secret parameter,
 * which means a workspace variable, which means the encrypted column.
 *
 * The sending half is not here at all, and that is deliberate. A plugin has no
 * network - no files, no sockets, no host - so it could not call Graph even if
 * it wanted to, and it should not want to: an outgoing call made from inside a
 * sandbox would go round the installation's proxy rules, which every other call
 * this product makes obeys. So a message is sent by an HTTP request action
 * against a connection, the way any other API is called here, and what this
 * plugin contributes is the shape of the request: `message` builds the body
 * Graph expects and `channelUrl` and `replyUrl` build the addresses, so nobody
 * has to keep Graph's spelling in their head or in a workflow's fields.
 *
 * Nothing on this file asks for a permission. Everything it does is arithmetic
 * and string handling, and the SHA-256 below is written out rather than reached
 * for so that loading it grants nothing at all.
 */

/** The Graph host every address below is built on. */
const GRAPH = 'https://graph.microsoft.com/v1.0';

/** SHA-256's round constants: the first thirty-two bits of the cube roots of the first sixty-four primes. */
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

const BASE64_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

function rotate(value, bits) {
  return ((value >>> bits) | (value << (32 - bits))) >>> 0;
}

/**
 * SHA-256 of some bytes, as thirty-two more.
 *
 * Written out rather than asked for because there is nothing to ask: the
 * sandbox has no `crypto`, and the one thing a plugin may request is a language
 * builtin. Sixty-four rounds over a request body is nothing against the ten
 * million statements a plugin is allowed.
 */
function sha256(message) {
  const state = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ]);

  // The message, a single one bit, zeroes, and the length in bits as the last
  // sixty-four - which is what makes the padding unambiguous.
  const padded = new Uint8Array((((message.length + 8) >> 6) + 1) << 6);
  padded.set(message);
  padded[message.length] = 0x80;

  const view = new DataView(padded.buffer);
  const bits = message.length * 8;
  view.setUint32(padded.length - 8, Math.floor(bits / 0x100000000));
  view.setUint32(padded.length - 4, bits >>> 0);

  const schedule = new Uint32Array(64);
  for (let block = 0; block < padded.length; block += 64) {
    for (let index = 0; index < 16; index += 1) {
      schedule[index] = view.getUint32(block + index * 4);
    }
    for (let index = 16; index < 64; index += 1) {
      const previous = schedule[index - 15];
      const recent = schedule[index - 2];
      const s0 = (rotate(previous, 7) ^ rotate(previous, 18) ^ (previous >>> 3)) >>> 0;
      const s1 = (rotate(recent, 17) ^ rotate(recent, 19) ^ (recent >>> 10)) >>> 0;
      schedule[index] = (schedule[index - 16] + s0 + schedule[index - 7] + s1) >>> 0;
    }

    let a = state[0];
    let b = state[1];
    let c = state[2];
    let d = state[3];
    let e = state[4];
    let f = state[5];
    let g = state[6];
    let h = state[7];

    for (let index = 0; index < 64; index += 1) {
      const s1 = (rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25)) >>> 0;
      const choice = ((e & f) ^ (~e & g)) >>> 0;
      const first = (h + s1 + choice + ROUND_CONSTANTS[index] + schedule[index]) >>> 0;
      const s0 = (rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22)) >>> 0;
      const majority = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
      const second = (s0 + majority) >>> 0;

      h = g;
      g = f;
      f = e;
      e = (d + first) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (first + second) >>> 0;
    }

    state[0] = (state[0] + a) >>> 0;
    state[1] = (state[1] + b) >>> 0;
    state[2] = (state[2] + c) >>> 0;
    state[3] = (state[3] + d) >>> 0;
    state[4] = (state[4] + e) >>> 0;
    state[5] = (state[5] + f) >>> 0;
    state[6] = (state[6] + g) >>> 0;
    state[7] = (state[7] + h) >>> 0;
  }

  const digest = new Uint8Array(32);
  const out = new DataView(digest.buffer);
  for (let word = 0; word < 8; word += 1) {
    out.setUint32(word * 4, state[word]);
  }
  return digest;
}

/** HMAC-SHA256, as the standard defines it: an inner hash of the message and an outer hash of that. */
function hmacSha256(key, message) {
  const block = new Uint8Array(64);
  block.set(key.length > 64 ? sha256(key) : key);

  const inner = new Uint8Array(64 + message.length);
  const outer = new Uint8Array(64 + 32);
  for (let at = 0; at < 64; at += 1) {
    inner[at] = block[at] ^ 0x36;
    outer[at] = block[at] ^ 0x5c;
  }
  inner.set(message, 64);
  outer.set(sha256(inner), 64);
  return sha256(outer);
}

/**
 * The bytes of some text, as UTF-8.
 *
 * `TextEncoder` would do this and is one of the permissions a plugin may ask
 * for, which is exactly why it is not used: a Teams webhook that has to be
 * accepted for a permission before it will verify anything is a worse thing to
 * hand somebody than twenty lines of encoding. Surrogate pairs are joined back
 * into one code point first, or a name with an emoji in it would hash
 * differently here than it did at Microsoft.
 */
function utf8(text) {
  const bytes = [];
  for (let at = 0; at < text.length; at += 1) {
    let code = text.charCodeAt(at);
    if (code >= 0xd800 && code <= 0xdbff && at + 1 < text.length) {
      const trailing = text.charCodeAt(at + 1);
      if (trailing >= 0xdc00 && trailing <= 0xdfff) {
        code = 0x10000 + ((code - 0xd800) << 10) + (trailing - 0xdc00);
        at += 1;
      }
    }

    if (code < 0x80) {
      bytes.push(code);
    } else if (code < 0x800) {
      bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
    } else if (code < 0x10000) {
      bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    } else {
      bytes.push(
        0xf0 | (code >> 18),
        0x80 | ((code >> 12) & 0x3f),
        0x80 | ((code >> 6) & 0x3f),
        0x80 | (code & 0x3f),
      );
    }
  }
  return new Uint8Array(bytes);
}

function base64Encode(bytes) {
  let text = '';
  for (let at = 0; at < bytes.length; at += 3) {
    const remaining = bytes.length - at;
    const first = bytes[at];
    const second = remaining > 1 ? bytes[at + 1] : 0;
    const third = remaining > 2 ? bytes[at + 2] : 0;

    text += BASE64_ALPHABET[first >> 2];
    text += BASE64_ALPHABET[((first & 0x03) << 4) | (second >> 4)];
    text += remaining > 1 ? BASE64_ALPHABET[((second & 0x0f) << 2) | (third >> 6)] : '=';
    text += remaining > 2 ? BASE64_ALPHABET[third & 0x3f] : '=';
  }
  return text;
}

/** The bytes some base64 stands for, or null when it is not base64 at all. */
function base64Decode(text) {
  const cleaned = String(text).replace(/[\s]/g, '').replace(/=+$/, '');
  if (!/^[A-Za-z0-9+/]*$/.test(cleaned)) {
    return null;
  }

  const bytes = [];
  let held = 0;
  let bits = 0;
  for (let at = 0; at < cleaned.length; at += 1) {
    held = (held << 6) | BASE64_ALPHABET.indexOf(cleaned[at]);
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((held >> bits) & 0xff);
    }
  }
  return new Uint8Array(bytes);
}

/**
 * Whether two signatures are the same, in time that does not depend on where
 * they first differ.
 *
 * A comparison that returns early tells whoever is guessing how much of their
 * guess was right, one request at a time. It is a long shot over the internet
 * and it costs one loop to close.
 */
function sameSignature(mine, theirs) {
  if (typeof theirs !== 'string' || mine.length !== theirs.length) {
    return false;
  }
  let difference = 0;
  for (let at = 0; at < mine.length; at += 1) {
    difference |= mine.charCodeAt(at) ^ theirs.charCodeAt(at);
  }
  return difference === 0;
}

/** A field off something that may not be an object at all, since this is reading a request. */
function field(holder, name) {
  return holder !== null && typeof holder === 'object' ? holder[name] : undefined;
}

function textOf(holder, name) {
  const value = field(holder, name);
  return typeof value === 'string' ? value : '';
}

export default class Teams extends OrknuxPlugin {

  id() {
    return 'teams';
  }

  apiVersion() {
    return 1;
  }

  parameters() {
    return [
      new OrknuxParameter({
        name: 'webhookSecret',
        description: 'The security token Teams showed when the outgoing webhook was created.',
        type: 'string',
        required: true,
        // Refuses a typed-in value, so the only way to answer it is to point at
        // one of the workspace's variables - which is the encrypted column.
        secret: true,
      }),
      new OrknuxParameter({
        name: 'webhookName',
        description: 'What the outgoing webhook is called in Teams, so its mention can be taken off the text.',
        type: 'string',
        required: false,
      }),
    ];
  }

  functions() {
    return [
      new OrknuxFunction({
        name: 'verify',
        description: 'Whether this request really came from the Teams outgoing webhook it claims to.',
        // Named to match what a webhook trigger hands its gatekeeper. The
        // signature is over the bytes that arrived, so it is `rawBody` and not
        // the parsed body that is hashed: re-serialising the JSON would reorder
        // a key or drop a space and every request would be refused.
        params: [{ name: 'headers', type: 'map' }, { name: 'rawBody', type: 'string' }],
        returnType: 'boolean',
        run: (headers, rawBody) => {
          const secret = this.settings.webhookSecret;
          if (typeof secret !== 'string' || secret.length === 0) {
            // Required, so a workspace that has not answered it is already
            // marked as needing to. Refusing everybody is the safe reading.
            return false;
          }
          const key = base64Decode(secret);
          if (key === null || key.length === 0) {
            return false;
          }

          // Teams sends "HMAC <signature>". The headers arrive lower-cased,
          // which is the one thing about them that can be relied on.
          const authorization = textOf(headers, 'authorization');
          const marker = authorization.indexOf(' ');
          if (marker < 0 || authorization.slice(0, marker).toUpperCase() !== 'HMAC') {
            return false;
          }

          const body = typeof rawBody === 'string' ? rawBody : '';
          const signature = base64Encode(hmacSha256(key, utf8(body)));
          return sameSignature(signature, authorization.slice(marker + 1).trim());
        },
      }),

      new OrknuxFunction({
        name: 'text',
        description: 'What was said, with the mention of the webhook and any markup taken off.',
        params: [{ name: 'activity', type: 'map' }],
        returnType: 'string',
        run: (activity) => {
          let said = textOf(activity, 'text');
          if (said.length === 0) {
            return '';
          }

          // Teams writes a mention as `<at>Name</at>`, and the name is the
          // webhook's own - so what is left is the instruction somebody typed
          // rather than the address they typed it to.
          const name = this.settings.webhookName;
          if (typeof name === 'string' && name.length > 0) {
            said = said.split('<at>' + name + '</at>').join(' ');
          }

          return said.replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();
        },
      }),

      new OrknuxFunction({
        name: 'sender',
        description: 'Who said it and where, as the fields a reply has to be addressed with.',
        params: [{ name: 'activity', type: 'map' }],
        returnType: 'map',
        run: (activity) => {
          const channelData = field(activity, 'channelData');
          return {
            user: textOf(field(activity, 'from'), 'name'),
            // Teams' own id for the person, which is what a mention in a reply
            // has to name; the display name is not addressable.
            userId: textOf(field(activity, 'from'), 'id'),
            aadObjectId: textOf(field(activity, 'from'), 'aadObjectId'),
            conversationId: textOf(field(activity, 'conversation'), 'id'),
            teamId: textOf(channelData, 'teamsTeamId'),
            channelId: textOf(channelData, 'teamsChannelId'),
            tenantId: textOf(field(channelData, 'tenant'), 'id'),
            messageId: textOf(activity, 'id'),
          };
        },
      }),

      new OrknuxFunction({
        name: 'message',
        description: 'The body of a Graph request that posts this text to a channel.',
        params: [{ name: 'text', type: 'string' }, { name: 'html', type: 'boolean' }],
        // A string and not a map, because this is the body of a request and a
        // request body is bytes. Handing back a map would leave whoever wired
        // the node guessing how it was going to be serialised.
        returnType: 'string',
        run: (text, html) => JSON.stringify({
          body: {
            contentType: html === true ? 'html' : 'text',
            content: typeof text === 'string' ? text : String(text ?? ''),
          },
        }),
      }),

      new OrknuxFunction({
        name: 'channelUrl',
        description: 'Where a new message in a channel is posted.',
        params: [{ name: 'teamId', type: 'string' }, { name: 'channelId', type: 'string' }],
        returnType: 'string',
        run: (teamId, channelId) =>
          GRAPH + '/teams/' + encodeURIComponent(teamId) + '/channels/' + encodeURIComponent(channelId) + '/messages',
      }),

      new OrknuxFunction({
        name: 'replyUrl',
        description: 'Where a reply under an existing message is posted.',
        params: [
          { name: 'teamId', type: 'string' },
          { name: 'channelId', type: 'string' },
          { name: 'messageId', type: 'string' },
        ],
        returnType: 'string',
        run: (teamId, channelId, messageId) =>
          GRAPH + '/teams/' + encodeURIComponent(teamId) + '/channels/' + encodeURIComponent(channelId) +
          '/messages/' + encodeURIComponent(messageId) + '/replies',
      }),
    ];
  }
}
