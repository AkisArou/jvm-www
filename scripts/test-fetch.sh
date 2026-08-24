#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/events" "$OUT/encoding" "$OUT/url" "$OUT/bodies" "$OUT/fetch"

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
  find "$ROOT/web-fetch-core/src/main/java" "$ROOT/web-fetch-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/bodies" \
  -d "$OUT/fetch" "${FETCH_SOURCES[@]}"
FETCH_CP="$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/bodies:$OUT/fetch"
java -cp "$FETCH_CP" io.github.akisarou.jvmwww.web.fetch.testkit.FetchConformance
java -cp "$FETCH_CP" io.github.akisarou.jvmwww.web.fetch.testkit.FetchEncodingConformance
java -cp "$FETCH_CP" io.github.akisarou.jvmwww.web.fetch.testkit.FetchUrlConformance
java -cp "$FETCH_CP" io.github.akisarou.jvmwww.web.fetch.testkit.FetchBodyConformance

fetch_operation_shape="$(
  javap -classpath "$FETCH_CP" -p io.github.akisarou.jvmwww.web.fetch.FetchOperation
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
  javap -classpath "$FETCH_CP" -p \
    io.github.akisarou.jvmwww.web.fetch.Request \
    io.github.akisarou.jvmwww.web.fetch.Fetch
)"
if [[ "$request_shape" != *"io.github.akisarou.jvmwww.web.url.URL"* ]] || \
   [[ "$request_shape" != *"io.github.akisarou.jvmwww.web.bodies.BufferedBodySource"* ]]; then
  printf 'Fetch Request must expose canonical URL and buffered body input boundaries\n' >&2
  exit 1
fi

transport_request_shape="$(
  javap -classpath "$FETCH_CP" -p io.github.akisarou.jvmwww.web.fetch.FetchTransportRequest
)"
if [[ "$transport_request_shape" != *"io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot body"* ]] || \
   [[ "$transport_request_shape" == *"BufferedBodySource"* ]] || \
   [[ "$transport_request_shape" == *"io.github.akisarou.jvmwww.web.bodies.Blob"* ]] || \
   [[ "$transport_request_shape" == *"io.github.akisarou.jvmwww.web.bodies.FormData"* ]]; then
  printf 'Fetch transport requests must share only immutable body snapshots, never owner objects\n' >&2
  exit 1
fi

response_shape="$(
  javap -classpath "$FETCH_CP" -p \
    io.github.akisarou.jvmwww.web.fetch.Response \
    'io.github.akisarou.jvmwww.web.fetch.Response$BodyReadPromise'
)"
if [[ "$response_shape" != *"io.github.akisarou.jvmwww.runtime.JsPromise blob()"* ]] || \
   [[ "$response_shape" != *"io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot body"* ]]; then
  printf 'Response must consume one immutable snapshot and expose Blob projection\n' >&2
  exit 1
fi

transport_request_verbose="$(
  javap -classpath "$FETCH_CP" -verbose io.github.akisarou.jvmwww.web.fetch.FetchTransportRequest
)"
if [[ "$transport_request_verbose" == *"io/github/akisarou/jvmwww/web/url/URL"* ]] || \
   [[ "$transport_request_verbose" == *"io/github/akisarou/jvmwww/web/bodies/BufferedBodySource"* ]]; then
  printf 'Fetch transport snapshots must not retain owner-confined URL or body objects\n' >&2
  exit 1
fi

mime_verbose="$(
  javap -classpath "$FETCH_CP" -verbose \
    io.github.akisarou.jvmwww.web.fetch.FetchMimeType \
    'io.github.akisarou.jvmwww.web.fetch.FetchMimeType$Extraction' \
    'io.github.akisarou.jvmwww.web.fetch.FetchMimeType$ParsedMimeType'
)"
if [[ "$mime_verbose" != *"java/util/Arrays.copyOf"* ]] || \
   grep -Eq 'java/util/(ArrayList|LinkedList|HashMap|LinkedHashMap|TreeMap)' <<<"$mime_verbose"; then
  printf 'Fetch MIME extraction must use compact parameter arrays rather than generic collections\n' >&2
  exit 1
fi

fetch_verbose="$(
  javap -classpath "$FETCH_CP" -verbose \
    io.github.akisarou.jvmwww.web.fetch.FetchOperation \
    io.github.akisarou.jvmwww.web.fetch.Fetch \
    io.github.akisarou.jvmwww.web.fetch.FetchTransportRequest \
    io.github.akisarou.jvmwww.web.fetch.Response \
    'io.github.akisarou.jvmwww.web.fetch.Response$BodyReadPromise' \
    io.github.akisarou.jvmwww.web.fetch.Request
)"
for required in \
  'io/github/akisarou/jvmwww/web/url/URL.getHref' \
  'io/github/akisarou/jvmwww/web/bodies/Blob.fromSnapshot' \
  'io/github/akisarou/jvmwww/web/bodies/BufferedBodySnapshot.copyBytes'; do
  if [[ "$fetch_verbose" != *"$required"* ]]; then
    printf 'web-fetch-core is missing canonical URL/body primitive: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq \
    'CompletableFuture|kotlinx/coroutines|ScheduledExecutorService|android/os/Handler|java/net/(URI|URL)|java/nio/charset|java/io/ByteArrayOutputStream|java/util/Base64|java/lang/Runnable' \
    <<<"$fetch_verbose"; then
  printf 'web-fetch-core must not introduce futures, schedulers, java.net/charset shortcuts, growing streams, base64, or Runnables\n' >&2
  exit 1
fi
