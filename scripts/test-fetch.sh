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
  -cp "$OUT/core:$OUT/encoding:$OUT/url" -d "$OUT/bodies" "${BODY_SOURCES[@]}"

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
java -cp "$FETCH_CP" io.github.akisarou.jvmwww.web.fetch.testkit.FetchBodyInitConformance
java -cp "$FETCH_CP" io.github.akisarou.jvmwww.web.fetch.testkit.FetchFormDataConformance
java -cp "$FETCH_CP" io.github.akisarou.jvmwww.web.fetch.testkit.FetchCloneConformance

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

body_read_shape="$(
  javap -classpath "$FETCH_CP" -p io.github.akisarou.jvmwww.web.fetch.FetchBodyReadPromise
)"
if [[ "$body_read_shape" != *"extends io.github.akisarou.jvmwww.runtime.JsPromise implements io.github.akisarou.jvmwww.runtime.RuntimeTask"* ]] || \
   [[ "$body_read_shape" == *"java.lang.Runnable"* ]]; then
  printf 'FetchBodyReadPromise must be the returned Promise and runtime microtask, never a Runnable\n' >&2
  exit 1
fi

request_shape="$(
  javap -classpath "$FETCH_CP" -p \
    io.github.akisarou.jvmwww.web.fetch.Request \
    io.github.akisarou.jvmwww.web.fetch.Fetch
)"
for required in \
  'io.github.akisarou.jvmwww.web.url.URL' \
  'io.github.akisarou.jvmwww.web.bodies.BufferedBodySource' \
  'io.github.akisarou.jvmwww.web.url.URLSearchParams' \
  'withStringBody' \
  'withSearchParamsBody' \
  'boolean isBodyUsed()' \
  'JsPromise arrayBuffer()' \
  'JsPromise bytes()' \
  'JsPromise text()' \
  'JsPromise blob()' \
  'JsPromise formData()' \
  'Request clone()' \
  'claimBodyForTransport()'; do
  if [[ "$request_shape" != *"$required"* ]]; then
    printf 'Fetch Request is missing canonical Body/clone boundary: %s\n' "$required" >&2
    exit 1
  fi
done

response_shape="$(
  javap -classpath "$FETCH_CP" -p io.github.akisarou.jvmwww.web.fetch.Response
)"
for required in \
  'JsPromise blob()' \
  'JsPromise formData()' \
  'Response clone()' \
  'io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot body'; do
  if [[ "$response_shape" != *"$required"* ]]; then
    printf 'Response is missing shared-snapshot Body/clone boundary: %s\n' "$required" >&2
    exit 1
  fi
done

if compgen -G \
     "$OUT/fetch/io/github/akisarou/jvmwww/web/fetch/Request\$*.class" \
     >/dev/null || \
   compgen -G \
     "$OUT/fetch/io/github/akisarou/jvmwww/web/fetch/Response\$*.class" \
     >/dev/null; then
  printf 'Request and Response readers must share FetchBodyReadPromise, not allocate inner task classes\n' >&2
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

transport_request_verbose="$(
  javap -classpath "$FETCH_CP" -verbose io.github.akisarou.jvmwww.web.fetch.FetchTransportRequest
)"
if [[ "$transport_request_verbose" != *"Request.claimBodyForTransport"* ]] || \
   [[ "$transport_request_verbose" == *"io/github/akisarou/jvmwww/web/url/URL"* ]] || \
   [[ "$transport_request_verbose" == *"io/github/akisarou/jvmwww/web/bodies/BufferedBodySource"* ]]; then
  printf 'Fetch transport snapshots must claim one immutable body and retain no owner objects\n' >&2
  exit 1
fi

clone_code="$(
  javap -classpath "$FETCH_CP" -p -c \
    io.github.akisarou.jvmwww.web.fetch.Request \
    io.github.akisarou.jvmwww.web.fetch.Response
)"
request_clone_section="$(
  sed -n \
    '/public io.github.akisarou.jvmwww.web.fetch.Request clone()/,/java.lang.String copyUrlForTransport/p' \
    <<<"$clone_code"
)"
response_clone_section="$(
  sed -n \
    '/public io.github.akisarou.jvmwww.web.fetch.Response clone()/,/private io.github.akisarou.jvmwww.runtime.JsPromise startBodyRead/p' \
    <<<"$clone_code"
)"
for required in \
  'AbortSignal.any' \
  'Headers."<init>":(Lio/github/akisarou/jvmwww/web/fetch/Headers;)V'; do
  if [[ "$request_clone_section" != *"$required"* ]]; then
    printf 'Request.clone is missing required metadata/signal primitive: %s\n' "$required" >&2
    exit 1
  fi
done
if [[ "$response_clone_section" != *"Headers.immutableCopy"* ]]; then
  printf 'Response.clone must copy immutable Headers metadata\n' >&2
  exit 1
fi
if grep -Eq \
    'BufferedBodySnapshot\.(copyBytes|copyOf)|BufferedBodySource\.snapshot|FormData\.snapshot|URL\."<init>"' \
    <<<"$request_clone_section$response_clone_section"; then
  printf 'Fetch clones must share immutable body snapshots without extraction, byte copy, or URL parsing\n' >&2
  exit 1
fi

mime_verbose="$(
  javap -classpath "$FETCH_CP" -verbose \
    io.github.akisarou.jvmwww.web.fetch.FetchMimeType \
    'io.github.akisarou.jvmwww.web.fetch.FetchMimeType$Extraction' \
    'io.github.akisarou.jvmwww.web.fetch.FetchMimeType$ParsedMimeType' \
    'io.github.akisarou.jvmwww.web.fetch.FetchMimeType$FormDataParameters'
)"
if [[ "$mime_verbose" != *"java/util/Arrays.copyOf"* ]] || \
   grep -Eq 'java/util/(ArrayList|LinkedList|HashMap|LinkedHashMap|TreeMap)' <<<"$mime_verbose"; then
  printf 'Fetch MIME extraction must use compact parameter arrays rather than generic collections\n' >&2
  exit 1
fi

form_body_verbose="$(
  javap -classpath "$FETCH_CP" -verbose \
    io.github.akisarou.jvmwww.web.url.URLSearchParams \
    io.github.akisarou.jvmwww.web.url.FormUrlCodec \
    io.github.akisarou.jvmwww.web.fetch.Request
)"
for required in \
  'io/github/akisarou/jvmwww/web/url/FormUrlCodec.serializeBytes' \
  'io/github/akisarou/jvmwww/web/url/URLSearchParams.copyFormEncodedBytes' \
  'io/github/akisarou/jvmwww/web/encoding/Utf8Codec.encode'; do
  if [[ "$form_body_verbose" != *"$required"* ]]; then
    printf 'selected BodyInit path is missing exact serialization primitive: %s\n' "$required" >&2
    exit 1
  fi
done
form_bytes_code="$(
  javap -classpath "$FETCH_CP" -p -c io.github.akisarou.jvmwww.web.url.FormUrlCodec
)"
form_bytes_section="$(
  sed -n '/static byte\[\] serializeBytes/,/private static void parseSequence/p' \
    <<<"$form_bytes_code"
)"
if grep -Eq 'StringBuilder|TextEncoder|Method serialize:' <<<"$form_bytes_section"; then
  printf 'URLSearchParams body serialization must not allocate an intermediate String or per-component UTF-8 array\n' >&2
  exit 1
fi

form_parser_verbose="$(
  javap -classpath "$FETCH_CP" -verbose \
    io.github.akisarou.jvmwww.web.fetch.Request \
    io.github.akisarou.jvmwww.web.fetch.Response \
    io.github.akisarou.jvmwww.web.fetch.FetchBodyReadPromise \
    io.github.akisarou.jvmwww.web.fetch.FetchMimeType \
    io.github.akisarou.jvmwww.web.bodies.FormDataParser \
    'io.github.akisarou.jvmwww.web.bodies.FormDataParser$MultipartParser'
)"
for required in \
  'io/github/akisarou/jvmwww/web/bodies/FormDataParser.parseUrlEncoded' \
  'io/github/akisarou/jvmwww/web/bodies/FormDataParser.parseMultipart' \
  'io/github/akisarou/jvmwww/web/bodies/BlobData.singleView' \
  'io/github/akisarou/jvmwww/web/encoding/TextDecoder.decode:([BII)'; do
  if [[ "$form_parser_verbose" != *"$required"* ]]; then
    printf 'Body.formData is missing direct bounded parser primitive: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq \
    'java/io/(ByteArrayOutputStream|ByteArrayInputStream)|java/util/regex|java/util/Scanner|java/util/(ArrayList|LinkedList|HashMap|LinkedHashMap|TreeMap)|java/nio/charset|java/util/Base64|CompletableFuture|kotlinx/coroutines|ScheduledExecutorService|ExecutorService|android/os/Handler|java/lang/Runnable' \
    <<<"$form_parser_verbose"; then
  printf 'Body.formData must use bounded byte parsing without streams, generic metadata graphs, charset/base64 shortcuts, or schedulers\n' >&2
  exit 1
fi

fetch_verbose="$(
  javap -classpath "$FETCH_CP" -verbose \
    io.github.akisarou.jvmwww.web.fetch.FetchOperation \
    io.github.akisarou.jvmwww.web.fetch.FetchBodyReadPromise \
    io.github.akisarou.jvmwww.web.fetch.Fetch \
    io.github.akisarou.jvmwww.web.fetch.FetchTransportRequest \
    io.github.akisarou.jvmwww.web.fetch.Response \
    io.github.akisarou.jvmwww.web.fetch.Request
)"
for required in \
  'io/github/akisarou/jvmwww/web/url/URL.getHref' \
  'io/github/akisarou/jvmwww/web/bodies/Blob.fromSnapshot' \
  'io/github/akisarou/jvmwww/web/bodies/BufferedBodySnapshot.copyBytes' \
  'io/github/akisarou/jvmwww/web/bodies/FormDataParser.parseMultipart' \
  'io/github/akisarou/jvmwww/web/fetch/FetchBodyReadPromise.start'; do
  if [[ "$fetch_verbose" != *"$required"* ]]; then
    printf 'web-fetch-core is missing canonical URL/body primitive: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq \
    'CompletableFuture|kotlinx/coroutines|ScheduledExecutorService|android/os/Handler|java/net/(URI|URL)|java/nio/charset|java/io/(ByteArrayOutputStream|ByteArrayInputStream)|java/util/regex|java/util/Base64|java/lang/Runnable|java/util/concurrent/Flow|ReadableStream' \
    <<<"$fetch_verbose"; then
  printf 'web-fetch-core must not introduce futures, schedulers, stream tees, java.net/charset shortcuts, growing streams, base64, or Runnables\n' >&2
  exit 1
fi
