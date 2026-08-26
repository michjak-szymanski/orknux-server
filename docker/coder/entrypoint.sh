#!/bin/sh
#
# Two things have to be true before the daemon starts: it has host keys that
# outlive this container, and it knows whose key to let in. Neither can be baked
# into the image - the first because a fingerprint that changes on every restart
# is a fingerprint nobody can check against, the second because an image
# carrying somebody's access is an image nobody else can pull.
set -eu

note() { printf '%s\n' "$*" >&2; }
fail() { note "$*"; exit 1; }

KEYS=/etc/ssh/keys
HOME_DIR=/home/coder
SSH_DIR="$HOME_DIR/.ssh"

# --------------------------------------------------------------- host identity

# Generated once, into a volume. `ssh-keygen -A` would write into /etc/ssh and
# be lost with the container, which is exactly the failure this exists to avoid.
if [ ! -f "$KEYS/ssh_host_ed25519_key" ]; then
    note "No host key yet - making one. Orknux records this on its first"
    note "connection and refuses a different one afterwards, so keep the volume."
    ssh-keygen -q -t ed25519 -N '' -f "$KEYS/ssh_host_ed25519_key"
    ssh-keygen -q -t rsa -b 4096 -N '' -f "$KEYS/ssh_host_rsa_key"
fi
chmod 600 "$KEYS"/ssh_host_*_key
chmod 644 "$KEYS"/ssh_host_*_key.pub

# Printed on every start, not only the first: this is what somebody compares
# against what the shell's page shows, and hunting for it in the logs of the
# start that happened to be first is not a thing anybody should have to do.
note "Host key fingerprint (this is what Orknux pins):"
ssh-keygen -lf "$KEYS/ssh_host_ed25519_key.pub" >&2

# ------------------------------------------------------------- who may come in

# Either a key in the environment or a file mounted in. Both are ordinary ways
# to run a container and neither puts the key in the image; what is refused is
# starting with no key at all, since a box nobody can reach that answers on 22
# is worse than one that says why it will not start.
mkdir -p "$SSH_DIR"
: > "$SSH_DIR/authorized_keys"

if [ -n "${ORKNUX_AUTHORIZED_KEYS:-}" ]; then
    printf '%s\n' "$ORKNUX_AUTHORIZED_KEYS" >> "$SSH_DIR/authorized_keys"
fi

if [ -f /run/secrets/authorized_keys ]; then
    cat /run/secrets/authorized_keys >> "$SSH_DIR/authorized_keys"
fi

if [ ! -s "$SSH_DIR/authorized_keys" ]; then
    fail "Nobody could get in, so this is not starting.

Give it a public key, either way round:

  -e ORKNUX_AUTHORIZED_KEYS=\"\$(cat ~/.ssh/orknux.pub)\"
  -v /path/to/orknux.pub:/run/secrets/authorized_keys:ro

The matching private key is what goes in the Shell, under Admin -> Shell.
Make a pair with:  ssh-keygen -t ed25519 -C orknux -f ./orknux -N ''"
fi

chown -R coder:coder "$SSH_DIR"
chmod 700 "$SSH_DIR"
chmod 600 "$SSH_DIR/authorized_keys"
note "Keys that may sign in: $(grep -c . "$SSH_DIR/authorized_keys")"

# --------------------------------------------------------------- the work area

# Only the directory itself, and only when it is empty. A recursive chown across
# a mounted checkout of somebody's repository rewrites ownership on every file in
# it and takes minutes on a large one; the directory alone is what a container
# starting on a fresh volume actually needs.
if [ -d /work ] && [ -z "$(ls -A /work 2>/dev/null)" ]; then
    chown coder:coder /work
fi

exec "$@"
