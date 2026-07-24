#!/usr/bin/env bash
# Run sbt on the host, or inside a Docker dev image when DOCKER_IMAGE is set, so the per-OS CI matrix
# (glibc/musl Linux containers; host macOS/Windows) is one reproducible command for CI and local use:
#
#   DOCKER_IMAGE=shuwariafrica/alpine-jdk:17 \
#     SNX_EXPECT_CLASSIFIER=linux-x86_64 SNX_EXPECT_OS=linux SNX_EXPECT_ENV=musl \
#     ./project/scripts/run-sbt.sh -batch "scripted platform/detect"
#
# SBT_PROPS, when set, is split on whitespace and prepended to the sbt argv. The container runs
# --user UID:GID so bind-mounted files keep caller ownership; HOME is a per-UID /tmp directory the sbt
# launcher can write to; the Coursier cache and the sbt 2.x cache store are bind-mounted from the host.
set -euo pipefail

extra_args=()
if [[ -n "${SBT_PROPS:-}" ]]; then
  read -ra extra_args <<< "$SBT_PROPS"
fi

# The sbt 2.x runner script defaults to the sbtn thin client, which starts a background server that
# CI's `-Dsbt.server.autostart=false` (SBT_OPTS) then stops from ever listening - the client exits 1
# on macOS and hangs to the job timeout on Windows. `--server` forces the classic foreground sbt in
# both branches; sbt's own main drops the flag from its command list, so it is inert wherever a
# wrapper forwards it into the JVM argv.
if [[ -z "${DOCKER_IMAGE:-}" ]]; then
  exec sbt --server ${extra_args[@]+"${extra_args[@]}"} "$@"
fi

mkdir -p "$HOME/.cache/coursier" "$HOME/.cache/sbt"
container_home="/tmp/snx-sbt-$(id -u)"
docker_args=(
  --rm
  --user "$(id -u):$(id -g)"
  -v "$PWD:$PWD"
  -v "$HOME/.cache/coursier:$HOME/.cache/coursier"
  -v "$HOME/.cache/sbt:$HOME/.cache/sbt"
  -w "$PWD"
  -e "HOME=$container_home"
  -e "COURSIER_CACHE=$HOME/.cache/coursier"
  -e "SBT_LOCAL_CACHE=$HOME/.cache/sbt"
)
for env_var in TERM CI SBT_OPTS SNX_EXPECT_CLASSIFIER SNX_EXPECT_OS SNX_EXPECT_ENV GNUPGHOME; do
  if [[ -n "${!env_var:-}" ]]; then
    docker_args+=(-e "$env_var")
  fi
done

# `--user UID:GID` has no /etc/passwd entry inside the image, so the JVM cannot resolve user.home from
# getpwuid and falls back to the literal "?"; scripted's publishLocal then writes to ?/.ivy2/local and the
# sub-build cannot resolve the plugin. Pin user.home to the writable per-UID HOME so both agree.
exec docker run "${docker_args[@]}" --entrypoint sh "$DOCKER_IMAGE" -c \
  'mkdir -p "$HOME" && git config --global --add safe.directory "*" && exec sbt -Duser.home="$HOME" --server "$@"' \
  sh ${extra_args[@]+"${extra_args[@]}"} "$@"
