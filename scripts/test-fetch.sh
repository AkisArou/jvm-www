#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/events" "$OUT/encoding" "$OUT/url" "$OUT/fetch"

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

mapfile -d '' URL_SOURCES < <(
  find "$ROOT/web-url/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/encoding" -d "$OUT/url" "${URL_SOURCES[@]}"

mapfile -d '' FETCH_SOURCES < <(
  find "$ROOT/web-fetch-core/src/main/java" "$ROOT/web-fetch-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url" -d "$OUT/fetch" "${FETCH_SOURCES[@]}"
java -cp "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/fetch" \
  io.github.akisarou.jvmwww.web.fetch.testkit.FetchConformance
java -cp "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/fetch" \
  io.github.akisarou.jvmwww.web.fetch.testkit.FetchEncodingConformance
java -cp "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/fetch" \
  io.github.akisarou.jvmwww.web.fetch.testkit.FetchUrlConformance

fetch_operation_shape="$(
  javap -classpath "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/fetch" -p \
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

request_shape="$(
  javap -classpath "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/fetch" -p \
    io.github.akisarou.jvmwww.web.fetch.Request \
    io.github.akisarou.jvmwww.web.fetch.Fetch
)"
if [[ "$request_shape" != *"io.github.akisarou.jvmwww.web.url.URL"* ]]; then
  printf 'Fetch Request and entry points must expose the canonical web-url input boundary\n' >&2
  exit 1
fi

transport_request_verbose="$(
  javap -classpath "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/fetch" -verbose \
    io.github.akisarou.jvmwww.web.fetch.FetchTransportRequest
)"
if [[ "$transport_request_verbose" == *"io/github/akisarou/jvmwww/web/url/URL"* ]]; then
  printf 'Fetch transport snapshots must carry canonical strings, not owner-confined URL objects\n' >&2
  exit 1
fi

fetch_verbose="$(
  javap -classpath "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/fetch" -verbose \
    io.github.akisarou.jvmwww.web.fetch.FetchOperation \
    io.github.akisarou.jvmwww.web.fetch.Fetch \
    io.github.akisarou.jvmwww.web.fetch.FetchTransportRequest \
    io.github.akisarou.jvmwww.web.fetch.Response \
    io.github.akisarou.jvmwww.web.fetch.Request
)"
if [[ "$fetch_verbose" != *"io/github/akisarou/jvmwww/web/url/URL.getHref"* ]]; then
  printf 'web-fetch-core must canonicalize request and response URLs through web-url\n' >&2
  exit 1
fi
if grep -Eq \
    'CompletableFuture|kotlinx/coroutines|ScheduledExecutorService|android/os/Handler|java/net/(URI|URL)|java/nio/charset|java/lang/Runnable' \
    <<<"$fetch_verbose"; then
  printf 'web-fetch-core must not introduce futures, coroutines, Android scheduling, java.net URL semantics, or JVM charset shortcuts\n' >&2
  exit 1
fi
