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
java -cp "$OUT/core:$OUT/encoding:$OUT/url" \
  io.github.akisarou.jvmwww.web.url.testkit.WebUrlConformance

url_shape="$(
  javap -classpath "$OUT/core:$OUT/encoding:$OUT/url" -p \
    io.github.akisarou.jvmwww.web.url.URL
)"
if [[ "$url_shape" != *"implements io.github.akisarou.jvmwww.web.url.URLSearchParamsUpdateTarget"* ]] || \
   [[ "$url_shape" == *"java.lang.Runnable"* ]]; then
  printf 'URL must own the live URLSearchParams update target without becoming a task or Runnable\n' >&2
  exit 1
fi

url_verbose="$(
  javap -classpath "$OUT/core:$OUT/encoding:$OUT/url" -verbose \
    io.github.akisarou.jvmwww.web.url.URL \
    io.github.akisarou.jvmwww.web.url.URLSearchParams \
    io.github.akisarou.jvmwww.web.url.UrlParser \
    io.github.akisarou.jvmwww.web.url.UrlHostParser \
    io.github.akisarou.jvmwww.web.url.UrlPath \
    io.github.akisarou.jvmwww.web.url.UrlRecord \
    io.github.akisarou.jvmwww.web.url.UrlPercentCodec \
    io.github.akisarou.jvmwww.web.url.FormUrlCodec \
    io.github.akisarou.jvmwww.web.url.UrlScalar
)"
if grep -Eq \
    'java/net/(URI|URL|URLEncoder|URLDecoder)|java/nio/charset|java/util/(HashMap|TreeMap)|CompletableFuture|kotlinx/coroutines|ScheduledExecutorService|ExecutorService|android/os/Handler|java/lang/Runnable' \
    <<<"$url_verbose"; then
  printf 'web-url must use selected URL/form/UTF-8 algorithms without java.net, charset wrappers, maps, or schedulers\n' >&2
  exit 1
fi
