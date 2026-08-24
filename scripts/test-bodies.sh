#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/encoding" "$OUT/url" "$OUT/bodies"

mapfile -d '' CORE_SOURCES < <(
  find "$ROOT/runtime-core/src/main/java" "$ROOT/runtime-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -d "$OUT/core" "${CORE_SOURCES[@]}"

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
  find "$ROOT/web-bodies/src/main/java" "$ROOT/web-bodies-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/encoding:$OUT/url" -d "$OUT/bodies" "${BODY_SOURCES[@]}"
BODIES_CP="$OUT/core:$OUT/encoding:$OUT/url:$OUT/bodies"
java -cp "$BODIES_CP" io.github.akisarou.jvmwww.web.bodies.testkit.BodiesConformance
java -cp "$BODIES_CP" \
  io.github.akisarou.jvmwww.web.bodies.testkit.FormDataParserConformance

blob_data_shape="$(
  javap -classpath "$BODIES_CP" -p io.github.akisarou.jvmwww.web.bodies.BlobData
)"
for required in \
  'byte[][] segments' \
  'int[] offsets' \
  'int[] lengths' \
  'long size' \
  'singleView(byte[], int, int)'; do
  if [[ "$blob_data_shape" != *"$required"* ]]; then
    printf 'BlobData is missing compact storage/range primitive: %s\n' "$required" >&2
    exit 1
  fi
done

read_shape="$(
  javap -classpath "$BODIES_CP" -p \
    'io.github.akisarou.jvmwww.web.bodies.Blob$BlobReadPromise'
)"
if [[ "$read_shape" != *"extends io.github.akisarou.jvmwww.runtime.JsPromise"* ]] || \
   [[ "$read_shape" != *"io.github.akisarou.jvmwww.runtime.RuntimeTask"* ]] || \
   [[ "$read_shape" == *"java.lang.Runnable"* ]]; then
  printf 'Blob reads must be Promise-backed runtime microtasks, never platform Runnables\n' >&2
  exit 1
fi

parser_shape="$(
  javap -classpath "$BODIES_CP" -p \
    io.github.akisarou.jvmwww.web.bodies.FormDataParser \
    'io.github.akisarou.jvmwww.web.bodies.FormDataParser$MultipartParser'
)"
for required in \
  'MAX_MULTIPART_PARTS' \
  'MAX_PART_HEADER_BYTES' \
  'MAX_PART_HEADERS' \
  'MAX_BOUNDARY_LENGTH'; do
  if [[ "$parser_shape" != *"$required"* ]]; then
    printf 'FormDataParser is missing explicit parser limit: %s\n' "$required" >&2
    exit 1
  fi
done

bodies_verbose="$(
  javap -classpath "$BODIES_CP" -verbose \
    io.github.akisarou.jvmwww.web.encoding.Utf8Codec \
    io.github.akisarou.jvmwww.web.encoding.TextDecoder \
    io.github.akisarou.jvmwww.web.url.FormUrlEncodedParser \
    io.github.akisarou.jvmwww.web.bodies.Blob \
    io.github.akisarou.jvmwww.web.bodies.BlobBuilder \
    io.github.akisarou.jvmwww.web.bodies.BlobData \
    io.github.akisarou.jvmwww.web.bodies.File \
    io.github.akisarou.jvmwww.web.bodies.FormData \
    io.github.akisarou.jvmwww.web.bodies.FormDataParser \
    'io.github.akisarou.jvmwww.web.bodies.FormDataParser$MultipartParser' \
    io.github.akisarou.jvmwww.web.bodies.DefaultMultipartBoundarySource \
    io.github.akisarou.jvmwww.web.bodies.MultipartEncoder
)"
for required in \
  'java/lang/System.arraycopy' \
  'java/util/UUID.randomUUID' \
  'io/github/akisarou/jvmwww/web/bodies/BlobData.singleView' \
  'io/github/akisarou/jvmwww/web/encoding/TextDecoder.decode:([BII)'; do
  if [[ "$bodies_verbose" != *"$required"* ]]; then
    printf 'web-bodies is missing required copy/range/boundary primitive: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq \
    'java/io/(ByteArrayOutputStream|ByteArrayInputStream)|java/nio/charset|java/util/Base64|java/util/regex|java/util/Scanner|java/util/(ArrayList|LinkedList|HashMap|LinkedHashMap|TreeMap)|CompletableFuture|kotlinx/coroutines|ScheduledExecutorService|ExecutorService|android/os/Handler|java/lang/Runnable' \
    <<<"$bodies_verbose"; then
  printf 'web-bodies must avoid streams, regex/MIME object graphs, generic collections, charset/base64 shortcuts, and schedulers\n' >&2
  exit 1
fi
