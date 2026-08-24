#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/events" "$OUT/encoding" "$OUT/url" "$OUT/bodies" "$OUT/fetch" "$OUT/okhttp"

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

mapfile -d '' BODY_SOURCES < <(
  find "$ROOT/web-bodies/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/encoding" -d "$OUT/bodies" "${BODY_SOURCES[@]}"

mapfile -d '' FETCH_SOURCES < <(
  find "$ROOT/web-fetch-core/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/bodies" \
  -d "$OUT/fetch" "${FETCH_SOURCES[@]}"

mapfile -d '' OKHTTP_SOURCES < <(
  find "$ROOT/web-fetch-okhttp/src/main/java" \
       "$ROOT/web-fetch-okhttp-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
OKHTTP_CP="$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/bodies:$OUT/fetch:$OUT/okhttp"
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/bodies:$OUT/fetch" \
  -d "$OUT/okhttp" "${OKHTTP_SOURCES[@]}"
java -cp "$OKHTTP_CP" \
  io.github.akisarou.jvmwww.web.fetch.okhttp.testkit.OkHttpFetchTransportConformance

bridge_shape="$(
  javap -classpath "$OKHTTP_CP" -p \
    'io.github.akisarou.jvmwww.web.fetch.okhttp.OkHttpFetchTransport$OkHttpFetchCall'
)"
if [[ "$bridge_shape" != *"implements okhttp3.Callback"* ]] || \
   [[ "$bridge_shape" != *"io.github.akisarou.jvmwww.web.fetch.FetchTransportCall"* ]] || \
   [[ "$bridge_shape" == *"java.lang.Runnable"* ]]; then
  printf 'OkHttp bridge must fuse Callback and FetchTransportCall without Runnable\n' >&2
  exit 1
fi

transport_verbose="$(
  javap -classpath "$OKHTTP_CP" -verbose \
    io.github.akisarou.jvmwww.web.fetch.okhttp.OkHttpFetchTransport \
    'io.github.akisarou.jvmwww.web.fetch.okhttp.OkHttpFetchTransport$OkHttpFetchCall'
)"
if grep -Eq \
    'CompletableFuture|kotlinx/coroutines|ScheduledExecutorService|ExecutorService|android/os/Handler|java/net/(URI|URL)|java/lang/Runnable' \
    <<<"$transport_verbose"; then
  printf 'web-fetch-okhttp must remain transport plumbing without another scheduler or URL semantics\n' >&2
  exit 1
fi
