#!/usr/bin/env bash
# Targets: bash 5 on CI's Linux runners (Ubuntu), and locally on a Linux host with docker. Prints what a Scala Native
# binary's start-up depends on here: the kernel and its signal-stack configuration, the CPU's vector and tile features,
# and the kernel's minimum signal stack; then, inside DOCKER_IMAGE when it is set (the container run-sbt.sh starts sbt
# in), that libc's SIGSTKSZ and whether sigaltstack accepts a stack of that size. It reports what it finds and fails
# only when it cannot run.
set -euo pipefail
shopt -s inherit_errexit
unset CDPATH

# A program that sizes an alternate signal stack the way Scala Native 0.5.12 does (SIGSTKSZ) and asks the kernel for it,
# then for 64 KiB.
readonly PROBE_SOURCE='#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#ifndef AT_MINSIGSTKSZ
#define AT_MINSIGSTKSZ 51
#endif
static void ask(size_t size) {
  stack_t stack = {0};
  stack.ss_size = size;
  stack.ss_sp = malloc(size);
  if (sigaltstack(&stack, NULL) == 0) printf("container: sigaltstack(%zu) accepted\n", size);
  else printf("container: sigaltstack(%zu) refused: %s\n", size, strerror(errno));
}
int main(void) {
  printf("container: SIGSTKSZ %ld, MINSIGSTKSZ %ld, AT_MINSIGSTKSZ %lu\n", (long)SIGSTKSZ, (long)MINSIGSTKSZ,
         getauxval(AT_MINSIGSTKSZ));
  ask(SIGSTKSZ);
  ask(65536);
  return 0;
}'

host_facts() {
  local release config cpu flags feature
  release=$(uname -r)
  printf 'host: kernel %s\n' "${release}"
  printf 'host: cmdline %s\n' "$(</proc/cmdline)"
  config="/boot/config-${release}"
  if [[ -r "${config}" ]]; then
    grep -E '^(# )?CONFIG_(DYNAMIC_SIGFRAME|STRICT_SIGALTSTACK_SIZE)[ =]' "${config}" | sed 's/^/host: /' || true # neither line: a kernel older than both options
  else
    printf 'host: %s is not readable\n' "${config}"
  fi
  cpu=$(grep -m 1 '^model name' /proc/cpuinfo | cut -d ':' -f 2-)
  printf 'host: cpu%s\n' "${cpu}"
  flags=$(grep -m 1 '^flags' /proc/cpuinfo)
  for feature in avx512f amx_tile amx_int8 amx_bf16 xfd; do
    if [[ " ${flags} " == *" ${feature} "* ]]; then
      printf 'host: cpu flag %s present\n' "${feature}"
    else
      printf 'host: cpu flag %s absent\n' "${feature}"
    fi
  done
  LD_SHOW_AUXV=1 /bin/true | grep 'MINSIGSTKSZ' | sed 's/^/host: /' || true # absent on a kernel that does not report it
}

container_facts() {
  command -v docker >/dev/null || {
    printf 'host-facts: DOCKER_IMAGE is set but docker is not on PATH\n' >&2
    exit 3
  }
  printf 'container: %s\n' "${DOCKER_IMAGE}"
  printf '%s\n' "${PROBE_SOURCE}" | docker run --rm -i --entrypoint sh "${DOCKER_IMAGE}" -c \
    'cat >/tmp/probe.c && clang -o /tmp/probe /tmp/probe.c && /tmp/probe'
}

main() {
  host_facts
  if [[ -n "${DOCKER_IMAGE:-}" ]]; then
    container_facts
  fi
}

if ! (return 0 2>/dev/null); then
  main "$@"
  exit
fi
