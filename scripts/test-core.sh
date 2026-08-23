#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

mapfile -d '' SOURCES < <(
  find \
    "$ROOT/runtime-core/src/main/java" \
    "$ROOT/runtime-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)

javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror -d "$OUT" "${SOURCES[@]}"
java -cp "$OUT" io.github.akisarou.jvmwww.testkit.RuntimeInstanceConformance
