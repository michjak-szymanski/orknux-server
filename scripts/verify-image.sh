#!/usr/bin/env bash
#
# Does the image actually run?
#
# The build proves the code compiles and the suite proves it behaves. Neither
# says anything about the artefact that gets published: the Dockerfile skips
# tests deliberately, and CI pushed the image without ever starting it. An image
# that cannot boot — a missing entrypoint, a jar that moved, a JRE without
# something the app needs, a non-root user that cannot read its own file — is
# built, pushed and tagged exactly like one that can.
#
# So this starts it, against a real Postgres, and asserts the things that are
# true of the *container* rather than of the application:
#
#   1. it boots and serves
#   2. Flyway migrated the schema it was given
#   3. security is enforced in the packaged image, not only in the dev profile
#   4. it runs as a non-root user, as the Dockerfile intends
#   5. the JVM is PID 1, so `docker stop` reaches it and shutdown is graceful
#   6. it stops within the grace period rather than being killed
#
# What it does not cover: Temporal. The image is started with the inline engine,
# because whether a separate service is reachable is not a property of this
# artefact. Worth knowing while reading a green run — with the default
# configuration the application *refuses to start* when Temporal is not up
# (`TemporalWorkerLifecycle` throws and the context fails), which is deliberate,
# and which means a deployment brought up before its Temporal will restart until
# that service answers.
#
# Usage:  scripts/verify-image.sh [image-tag]
# Exits non-zero, loudly, on the first thing that is not true.

set -euo pipefail

IMAGE="${1:-orknux-server:verify}"
NET="orknux-verify-net"
DB="orknux-verify-db"
APP="orknux-verify-app"
PORT="18080"

# A key of the right shape. Nothing here is kept, so it may be anything valid;
# a wrong one would fail the credential checks rather than the boot.
SECRET_KEY="$(head -c 32 /dev/urandom | base64)"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()  { printf '  \033[32mok\033[0m   %s\n' "$*"; }
die() { printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

cleanup() {
  # Logs first: a failure that takes its evidence with it is a failure nobody
  # can fix from a CI transcript.
  if [ "${KEEP:-}" = "1" ]; then
    printf '\nKEEP=1, leaving %s and %s running\n' "$APP" "$DB"
    return
  fi
  docker rm -f "$APP" "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

say "Building $IMAGE"
docker build -t "$IMAGE" .

say "Starting Postgres"
docker network create "$NET" >/dev/null 2>&1 || true
docker run -d --name "$DB" --network "$NET" \
  -e POSTGRES_DB=orknux -e POSTGRES_USER=orknux -e POSTGRES_PASSWORD=orknux \
  postgres:18 >/dev/null

for _ in $(seq 1 30); do
  docker exec "$DB" pg_isready -U orknux -d orknux >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$DB" pg_isready -U orknux -d orknux >/dev/null 2>&1 || die "Postgres never became ready"
ok "Postgres is up"

say "Starting the image"
docker run -d --name "$APP" --network "$NET" -p "$PORT:8080" \
  -e ORKNUX_DB_URL="jdbc:postgresql://$DB:5432/orknux" \
  -e ORKNUX_SECRET_KEY="$SECRET_KEY" \
  -e ORKNUX_TEMPORAL_ENABLED=false \
  "$IMAGE" >/dev/null

# 1. It boots and serves.
#
# Asked of `/api/auth/method`, which is open by design — the sign-in screen has
# to read it before anybody has signed in. `/actuator/health` needs a session,
# so an unauthenticated probe of it proves nothing about booting.
say "Waiting for it to answer"
started=""
for _ in $(seq 1 90); do
  if curl -fsS "http://localhost:$PORT/api/auth/method" >/dev/null 2>&1; then started="yes"; break; fi
  # A container that died is not going to start answering, so say so now
  # rather than after ninety seconds of politeness.
  if [ "$(docker inspect -f '{{.State.Running}}' "$APP" 2>/dev/null)" != "true" ]; then
    docker logs "$APP" 2>&1 | tail -40
    die "The container exited while starting"
  fi
  sleep 1
done
[ -n "$started" ] || { docker logs "$APP" 2>&1 | tail -40; die "It never answered within 90s"; }
ok "It boots and serves"

# 2. Flyway ran. The schema is what the application is useless without, and a
#    migration that fails leaves the app up and every query broken.
say "Checking the schema"
version="$(docker exec "$DB" psql -U orknux -d orknux -tAc \
  "SELECT max(version::numeric) FROM flyway_schema_history WHERE success" 2>/dev/null || true)"
[ -n "$version" ] || die "No flyway_schema_history — the migrations did not run"
failed="$(docker exec "$DB" psql -U orknux -d orknux -tAc \
  "SELECT count(*) FROM flyway_schema_history WHERE NOT success")"
[ "$failed" = "0" ] || die "$failed migrations failed"
ok "Schema migrated to v$version, nothing failed"

# 3. Security is on in the packaged image. A profile that only guards the dev
#    run would be found here rather than by somebody else.
say "Checking that it refuses anonymous callers"
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:$PORT/graphql" \
  -H 'content-type: application/json' -d '{"query":"{ workspaces { content { id } } }"}')"
[ "$code" = "401" ] || die "An anonymous GraphQL call answered $code, expected 401"
ok "Anonymous calls are refused"

# 4. Not root. The Dockerfile says so; this checks the image was built the way
#    it reads.
say "Checking the user"
who="$(docker exec "$APP" id -un)"
[ "$who" = "orknux" ] || die "Running as $who, expected orknux"
ok "Runs as $who"

# 5. The JVM is PID 1. The entrypoint uses `exec` for this reason: without it a
#    shell holds PID 1, `docker stop` signals the shell, and the JVM is killed
#    at the end of the grace period instead of shutting down.
say "Checking PID 1"
pid1="$(docker exec "$APP" cat /proc/1/cmdline | tr '\0' ' ' | awk '{print $1}')"
case "$pid1" in
  *java) ok "PID 1 is $pid1" ;;
  *) die "PID 1 is '$pid1', not the JVM — docker stop will not reach it" ;;
esac

# 6. It stops when asked. Anything slower than the grace period is a container
#    that gets killed on every deploy, mid-request.
say "Checking that it stops gracefully"
began="$(date +%s)"
docker stop -t 30 "$APP" >/dev/null
took=$(( $(date +%s) - began ))
[ "$took" -lt 30 ] || die "It did not stop within the grace period; it was killed"

# 143 is 128+SIGTERM, which is what a JVM reports after `docker stop` even when
# its shutdown hooks ran to completion — the signal is how it was asked to stop,
# not evidence that anything went wrong. What would be evidence is 137
# (128+SIGKILL): that is the daemon losing patience at the end of the grace
# period, which means shutdown did not finish and requests in flight were cut.
code="$(docker inspect -f '{{.State.ExitCode}}' "$APP")"
case "$code" in
  0|143) ok "Stopped in ${took}s, exit $code" ;;
  137) die "It was killed after the grace period — shutdown did not finish" ;;
  *) die "It exited $code, which is neither a clean stop nor a signal" ;;
esac

printf '\n\033[32mThe image works.\033[0m\n'
