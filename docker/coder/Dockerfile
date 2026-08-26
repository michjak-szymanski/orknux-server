# A box an agent can develop JVM software in, reached over SSH.
#
# This is not the image the product ships in. It is a *target*: something a
# workspace's Shell points at, so an agent given the SHELLS capability can build,
# test and run code somewhere that is not the server. Orknux's own stack is
# Kotlin on Java 25 with Maven, but the point of this box is that it is not
# limited to that - a task set on somebody else's Gradle-and-Java-21 project has
# to work here too, which is why several JDKs and both build tools are present.
#
# WHAT IT DOES NOT HAVE, ON PURPOSE
#
# No password login. Orknux authenticates a shell with a private key and has
# nowhere to put a password (see `Shell.privateKey`), so a password on this box
# would be a door the product cannot use and an attacker can. `sshd` is
# configured to refuse them.
#
# No baked-in key. The public key arrives at run time, so the image can be
# rebuilt, published and pulled without carrying somebody's access with it.
#
# No Docker socket. Handing a container the host's socket is handing it the
# host; if a task needs to build images, give it its own daemon rather than
# yours.
FROM eclipse-temurin:25-jdk-noble

# Everything in one layer, and the lists dropped afterwards: an image somebody
# pulls onto a build machine is worth keeping small, and apt's lists are 40MB of
# nothing anybody needs once the packages are in.
#
# The groups, in order: the ssh daemon itself; what a build reaches for
# (git, curl, unzip); what a person reaching for a shell expects to find (a
# pager, an editor, a process list); and what makes reading a repository
# bearable at the end of a pipe (ripgrep, jq).
RUN apt-get update && apt-get install -y --no-install-recommends \
        openssh-server \
        ca-certificates curl wget gnupg unzip zip tar xz-utils \
        git openssh-client \
        build-essential pkg-config \
        less vim-tiny nano procps htop tree \
        ripgrep jq \
        python3 python3-venv \
    && rm -rf /var/lib/apt/lists/*

# Node, because a JVM application with a web interface is the ordinary case
# rather than the exception - this very product is one - and a task that can
# build the server but not the pages it serves is half a box.
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# The account work is done as. Not root: a task that runs as root leaves files
# nobody else can clean up, and every mistake it makes is an unrecoverable one.
# `coder` owns its own home and nothing above it.
# 1000 deliberately, and taken from Ubuntu's own placeholder account rather than
# stepping around it: a checkout bind-mounted from a Linux host is almost always
# owned by 1000, and a box whose user is 1001 writes files its owner then cannot
# edit. Noble ships an `ubuntu` account sitting on it, so that one is renamed
# into this rather than a second being made beside it.
ARG CODER_UID=1000
ARG CODER_GID=1000
RUN if getent passwd "${CODER_UID}" >/dev/null; then \
        old="$(getent passwd "${CODER_UID}" | cut -d: -f1)"; \
        groupmod -n coder "$(getent group "${CODER_GID}" | cut -d: -f1)"; \
        usermod -l coder -d /home/coder -m -s /bin/bash "$old"; \
    else \
        groupadd -g "${CODER_GID}" coder \
        && useradd -m -u "${CODER_UID}" -g coder -s /bin/bash coder; \
    fi \
    # And unlocked, which is not the same as given a password. Ubuntu ships its
    # placeholder account with `!` for a hash, and `UsePAM no` reads that as
    # locked: the key exchange succeeds and sshd then refuses with "account is
    # locked", which reads like a key problem and is not one. `*` is matched by
    # no password either, so nothing is opened by saying it.
    && usermod -p '*' coder

# SDKMAN, and the toolchains under it.
#
# Installed as `coder` into `coder`'s home, so a task can add a JDK or a build
# tool for itself later without being root - which is the whole reason for
# preferring it to apt here. What is installed now is the common ground: two
# JDKs either side of the one this product uses, Maven and Gradle, and the
# Kotlin compiler for a script that is not a project.
#
# The base image's own JDK 25 is registered with SDKMAN rather than downloaded
# again - `sdk install java 25-base /opt/java/openjdk` names what is already
# there - so both are switchable and neither is a second copy. 25 is default
# because that is what Orknux builds against; a task needing the other says
# `sdk use java 21.0.5-tem` and has it for that shell alone.
USER coder
ENV SDKMAN_DIR=/home/coder/.sdkman
RUN curl -fsSL https://get.sdkman.io?rcupdate=false | bash \
    && bash -lc 'source "$SDKMAN_DIR/bin/sdkman-init.sh" \
        && sdk install java 21.0.5-tem \
        && sdk install java 25-base /opt/java/openjdk \
        && sdk default java 25-base \
        && sdk install maven \
        && sdk install gradle \
        && sdk install kotlin \
        && sdk flush archives && sdk flush temp'

# On PATH for `ssh box "mvn -v"`, not only for somebody typing.
#
# Three shells have to agree and they read different files. An interactive
# session reads `.bashrc`; a login shell reads `.profile`; and `ssh box command`
# is neither - bash run by sshd over a network connection reads `.bashrc` and
# nothing else.
#
# Which is where the trap is: Ubuntu's stock `.bashrc` opens with `case $- in
# *i*) ;; *) return;; esac`, so a non-interactive shell leaves before reading a
# line of it. Appending to that file therefore works when a person types and
# fails when an agent drives - the one case this box exists for. So the source
# line goes in *front* of the guard, and the environment it sources is a file of
# its own that all three read.
RUN <<'SETUP'
set -eu
cat > /home/coder/.bash_env <<'ENV'
export SDKMAN_DIR="$HOME/.sdkman"
export JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ] && . "$SDKMAN_DIR/bin/sdkman-init.sh"
ENV
printf '%s\n' '. "$HOME/.bash_env"' | cat - /home/coder/.bashrc > /home/coder/.bashrc.new
mv /home/coder/.bashrc.new /home/coder/.bashrc
printf '%s\n' '. "$HOME/.bash_env"' >> /home/coder/.profile
SETUP

# Where work goes, and where the host keys live.
#
# Both are volumes in the compose file beside this. `/etc/ssh/keys` matters more
# than it looks: Orknux records the host key it first saw as a `SHA256:`
# fingerprint and refuses to connect when it changes - trust on first use, the
# same thing a person does by typing yes at `ssh`. An image that generated fresh
# host keys on every start would therefore break its own shell on every restart,
# and the fix somebody would reach for is clearing the fingerprint, which is the
# one check standing between this and handing a private key to whatever answers
# on that address today.
USER root
RUN mkdir -p /work /etc/ssh/keys /var/run/sshd \
    && chown -R coder:coder /work

COPY --chmod=0755 entrypoint.sh /usr/local/bin/entrypoint.sh
COPY sshd_config /etc/ssh/sshd_config.orknux

EXPOSE 22
WORKDIR /work

# A shell that cannot be reached is not a shell, so the daemon is the process
# this container is: it dies, the container dies, and whatever restarts things
# gets to say so.
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["/usr/sbin/sshd", "-D", "-e", "-f", "/etc/ssh/sshd_config.orknux"]
