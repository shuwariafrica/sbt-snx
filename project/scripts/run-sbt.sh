#!/usr/bin/env bash
# Run sbt on the host, or inside a Docker dev image when DOCKER_IMAGE is set, so the per-OS CI matrix
# (glibc/musl Linux containers; host macOS/Windows) is one reproducible command for CI and local use:
#
#   DOCKER_IMAGE=shuwariafrica/alpine-jdk:21 \
#     SNX_EXPECT_CLASSIFIER=linux-x86_64 SNX_EXPECT_OS=linux SNX_EXPECT_ENV=musl \
#     ./project/scripts/run-sbt.sh -batch "scripted platform/detect"
#
# SBT_PROPS, when set, is split on whitespace and prepended to the sbt argv. The container runs
# --user UID:GID so bind-mounted files keep caller ownership; HOME is a per-UID /tmp directory the sbt
# launcher can write to; the Coursier cache and the sbt 2.x cache store are bind-mounted from the host.
#
# Runs under bash 3.2, the macOS cell's own, and later - hence no `inherit_errexit` (bash 4.4); no
# substitution here calls a function or a list, so nothing relies on it.
set -euo pipefail

main() {
  local extra_args=()
  if [[ -n "${SBT_PROPS:-}" ]]; then
    read -ra extra_args <<<"${SBT_PROPS}"
  fi

  # The sbt 2.x runner script defaults to the sbtn thin client, which starts a background server that
  # CI's `-Dsbt.server.autostart=false` (SBT_OPTS) then stops from ever listening - the client exits 1
  # on macOS and hangs to the job timeout on Windows. `--server` forces the classic foreground sbt in
  # both branches; sbt's own main drops the flag from its command list, so it is inert wherever a
  # wrapper forwards it into the JVM argv.
  if [[ -z "${DOCKER_IMAGE:-}" ]]; then
    exec sbt --server ${extra_args[@]+"${extra_args[@]}"} "$@"
  fi

  local uid gid
  uid=$(id -u)
  gid=$(id -g)
  mkdir -p "${HOME}/.cache/coursier" "${HOME}/.cache/sbt"
  local docker_args=(
    --rm
    --user "${uid}:${gid}"
    -v "${PWD}:${PWD}"
    -v "${HOME}/.cache/coursier:${HOME}/.cache/coursier"
    -v "${HOME}/.cache/sbt:${HOME}/.cache/sbt"
    -w "${PWD}"
    -e "HOME=/tmp/snx-sbt-${uid}"
    -e "COURSIER_CACHE=${HOME}/.cache/coursier"
    -e "SBT_LOCAL_CACHE=${HOME}/.cache/sbt"
  )
  local env_var
  for env_var in TERM CI SBT_OPTS SNX_EXPECT_CLASSIFIER SNX_EXPECT_OS SNX_EXPECT_ENV GNUPGHOME; do
    if [[ -n "${!env_var:-}" ]]; then
      docker_args+=(-e "${env_var}")
    fi
  done

  # `--user UID:GID` has no /etc/passwd entry inside the image, so the JVM reports user.home as the literal "?".
  # sbt itself falls back to HOME, but the launcher that boots every scripted sub-build resolves its boot directory
  # and repositories from user.home, so pin it to the writable per-UID HOME.
  exec docker run "${docker_args[@]}" --entrypoint sh "${DOCKER_IMAGE}" -c \
    'mkdir -p "$HOME" && git config --global --add safe.directory "*" && exec sbt -Duser.home="$HOME" --server "$@"' \
    sh ${extra_args[@]+"${extra_args[@]}"} "$@"
}

if ! (return 0 2>/dev/null); then
  main "$@"
  exit
fi
