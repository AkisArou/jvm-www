#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/events" "$OUT/encoding" "$OUT/fetch"

mapfile -d '' CORE_SOURCES < <(
  find "$ROOT/runtime-core/src/main/java" "$ROOT/runtime-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -d "$OUT/core" "${CORE_SOURCES[@]}"

mapfile -d '' EVENT_SOURCES < <(
  find "$ROOT/web-events/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core" -d "$OUT/events" "${EVENT_SOURCES[@]}"

mapfile -d '' ENCODING_SOURCES < <(
  find "$ROOT/web-encoding/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core" -d "$OUT/encoding" "${ENCODING_SOURCES[@]}"

mapfile -d '' FETCH_SOURCES < <(
  find "$ROOT/web-fetch-core/src/main/java" "$ROOT/web-fetch-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/events:$OUT/encoding" -d "$OUT/fetch" "${FETCH_SOURCES[@]}"
java -cp "$OUT/core:$OUT/events:$OUT/encoding:$OUT/fetch" \
  io.github.akisarou.jvmwww.web.fetch.testkit.FetchConformance
java -cp "$OUT/core:$OUT/events:$OUT/encoding:$OUT/fetch" \
  io.github.akisarou.jvmwww.web.fetch.testkit.FetchEncodingConformance

fetch_operation_shape="$(
  javap -classpath "$OUT/core:$OUT/events:$OUT/encoding:$OUT/fetch" -p \
    io.github.akisarou.jvmwww.web.fetch.FetchOperation
)"
if [[ "$fetch_operation_shape" != *"extends io.github.akisarou.jvmwww.runtime.JsPromise"* ]] || \
   [[ "$fetch_operation_shape" != *"io.github.akisarou.jvmwww.web.fetch.FetchTransportCallback"* ]] || \
   [[ "$fetch_operation_shape" != *"io.github.akisarou.jvmwww.web.events.AbortAlgorithm"* ]] || \
   [[ "$fetch_operation_shape" != *"io.github.akisarou.jvmwww.runtime.RuntimeTask"* ]] || \
   [[ "$fetch_operation_shape" == *"java.lang.Runnable"* ]]; then
  printf 'FetchOperation must fuse Promise, transport callback, abort algorithm, and RuntimeTask\n' >&2
  exit 1
fi

fetch_verbose="$(
  javap -classpath "$OUT/core:$OUT/events:$OUT/encoding:$OUT/fetch" -verbose \
    io.github.akisarou.jvmwww.web.fetch.FetchOperation \
    io.github.akisarou.jvmwww.web.fetch.Fetch \
    io.github.akisarou.jvmwww.web.fetch.Response \
    io.github.akisarou.jvmwww.web.fetch.Request
)"
if grep -Eq \
    'CompletableFuture|kotlinx/coroutines|ScheduledExecutorService|android/os/Handler|java/net/(URI|URL)|java/nio/charset|java/lang/Runnable' \
    <<<"$fetch_verbose"; then
  printf 'web-fetch-core must not introduce futures, coroutines, Android scheduling, java.net URL semantics, or JVM charset shortcuts\n' >&2
  exit 1
fi
