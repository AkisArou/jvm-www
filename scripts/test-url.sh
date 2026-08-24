#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/encoding" "$OUT/url"

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
  find "$ROOT/web-url/src/main/java" "$ROOT/web-url-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/encoding" -d "$OUT/url" "${URL_SOURCES[@]}"
URL_CP="$OUT/core:$OUT/encoding:$OUT/url"
java -cp "$URL_CP" io.github.akisarou.jvmwww.web.url.testkit.WebUrlConformance

url_shape="$(
  javap -classpath "$URL_CP" -p \
    io.github.akisarou.jvmwww.web.url.URL \
    io.github.akisarou.jvmwww.web.url.URLSearchParams \
    io.github.akisarou.jvmwww.web.url.FormUrlEncodedParser
)"
if [[ "$url_shape" != *"implements io.github.akisarou.jvmwww.web.url.URLSearchParamsUpdateTarget"* ]] || \
   [[ "$url_shape" != *"implements io.github.akisarou.jvmwww.web.url.FormUrlEncodedConsumer"* ]] || \
   [[ "$url_shape" == *"java.lang.Runnable"* ]]; then
  printf 'URL/URLSearchParams must own live/parser sinks without becoming tasks or Runnables\n' >&2
  exit 1
fi

url_verbose="$(
  javap -classpath "$URL_CP" -verbose \
    io.github.akisarou.jvmwww.web.url.URL \
    io.github.akisarou.jvmwww.web.url.URLSearchParams \
    io.github.akisarou.jvmwww.web.url.FormUrlEncodedParser \
    io.github.akisarou.jvmwww.web.url.UrlParser \
    io.github.akisarou.jvmwww.web.url.UrlHostParser \
    io.github.akisarou.jvmwww.web.url.UrlPath \
    io.github.akisarou.jvmwww.web.url.UrlRecord \
    io.github.akisarou.jvmwww.web.url.UrlPercentCodec \
    io.github.akisarou.jvmwww.web.url.FormUrlCodec \
    io.github.akisarou.jvmwww.web.url.UrlScalar
)"
for required in \
  'io/github/akisarou/jvmwww/web/url/FormUrlCodec.serializeBytes' \
  'io/github/akisarou/jvmwww/web/encoding/TextDecoder.decode:([BII)'; do
  if [[ "$url_verbose" != *"$required"* ]]; then
    printf 'web-url is missing exact form serialization/range-decoding primitive: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq \
    'java/net/(URI|URL|URLEncoder|URLDecoder)|java/nio/charset|java/io/(ByteArrayOutputStream|ByteArrayInputStream)|java/util/regex|java/util/(HashMap|TreeMap)|CompletableFuture|kotlinx/coroutines|ScheduledExecutorService|ExecutorService|android/os/Handler|java/lang/Runnable' \
    <<<"$url_verbose"; then
  printf 'web-url must use selected URL/form/UTF-8 algorithms without java.net, streams, charset wrappers, maps, regex, or schedulers\n' >&2
  exit 1
fi
