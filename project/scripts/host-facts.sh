#!/usr/bin/env bash
# Targets: bash 5 on CI's Linux runners (Ubuntu), and locally on a Linux host with docker. Prints what a Scala Native
# binary's start-up depends on here: the kernel and its signal-stack configuration, the CPU's vector and tile features,
# and the kernel's minimum signal stack; then, inside DOCKER_IMAGE when it is set (the container run-sbt.sh starts sbt
# in), that libc's SIGSTKSZ and whether sigaltstack accepts a stack of that size. It reports what it finds and fails
# only when it cannot run.
set -euo pipefail
shopt -s inherit_errexit
unset CDPATH

# A program that sizes an alternate signal stack the way Scala Native 0.5.13 does - the larger of SIGSTKSZ and
# sysconf(_SC_SIGSTKSZ), which follows the kernel's minimum - and asks the kernel for it, then for SIGSTKSZ alone (the
# size earlier releases asked for) and for 64 KiB.
readonly PROBE_SOURCE='#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <unistd.h>
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
  size_t size = (size_t)SIGSTKSZ;
  long runtime = -1;
#ifdef _SC_SIGSTKSZ
  runtime = sysconf(_SC_SIGSTKSZ);
  if (runtime > 0 && (size_t)runtime > size) size = (size_t)runtime;
#endif
  printf("container: SIGSTKSZ %ld, MINSIGSTKSZ %ld, sysconf(_SC_SIGSTKSZ) %ld, AT_MINSIGSTKSZ %lu\n", (long)SIGSTKSZ,
         (long)MINSIGSTKSZ, runtime, getauxval(AT_MINSIGSTKSZ));
  ask(size);
  ask(SIGSTKSZ);
  ask(65536);
  return 0;
}'

host_facts() {
  local release config machine cpu flags feature
  local -a features
  release=$(uname -r)
  machine=$(uname -m)
  printf 'host: kernel %s (%s)\n' "${release}" "${machine}"
  printf 'host: cmdline %s\n' "$(</proc/cmdline)"
  config="/boot/config-${release}"
  if [[ -r "${config}" ]]; then
    grep -E '^(# )?CONFIG_(DYNAMIC_SIGFRAME|STRICT_SIGALTSTACK_SIZE|ARM64_SVE|ARM64_SME)[ =]' "${config}" | sed 's/^/host: /' || true # none: an architecture or kernel without these options
  else
    printf 'host: %s is not readable\n' "${config}"
  fi
  # x86 names its model and lists `flags`; arm64 names an implementer and part and lists `Features`. The features are
  # what grows the signal frame on each: AVX-512 and the AMX tiles behind XFD, or SVE and SME.
  case "${machine}" in
    x86_64) features=(avx512f amx_tile amx_int8 amx_bf16 xfd) ;;
    aarch64 | arm64) features=(sve sve2 sme) ;;
    *) features=() ;;
  esac
  cpu=$(grep -E '^(model name|CPU implementer|CPU part)' /proc/cpuinfo | sort -u | cut -d ':' -f 2- | tr -d '\n') || cpu=' not reported' # neither line on this architecture
  printf 'host: cpu%s\n' "${cpu}"
  flags=$(grep -m 1 -E '^(flags|Features)' /proc/cpuinfo) || flags='' # no feature line on this architecture
  for feature in ${features[@]+"${features[@]}"}; do
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
