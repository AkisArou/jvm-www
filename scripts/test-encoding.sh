#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/encoding"

mapfile -d '' CORE_SOURCES < <(
  find "$ROOT/runtime-core/src/main/java" "$ROOT/runtime-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -d "$OUT/core" "${CORE_SOURCES[@]}"

mapfile -d '' ENCODING_SOURCES < <(
  find "$ROOT/web-encoding/src/main/java" "$ROOT/web-encoding-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core" -d "$OUT/encoding" "${ENCODING_SOURCES[@]}"
java -cp "$OUT/core:$OUT/encoding" \
  io.github.akisarou.jvmwww.web.encoding.testkit.EncodingConformance
java -cp "$OUT/core:$OUT/encoding" \
  io.github.akisarou.jvmwww.web.encoding.testkit.EncodingRangeConformance

encoding_shape="$(
  javap -classpath "$OUT/core:$OUT/encoding" -p \
    io.github.akisarou.jvmwww.web.encoding.TextEncoder \
    io.github.akisarou.jvmwww.web.encoding.TextDecoder
)"
if [[ "$encoding_shape" != *"decode(byte[], int, int)"* ]]; then
  printf 'TextDecoder must expose direct immutable byte-range decoding\n' >&2
  exit 1
fi
if [[ "$encoding_shape" == *"java.lang.Runnable"* ]] || \
   [[ "$encoding_shape" == *"java.nio.charset.CharsetDecoder"* ]] || \
   [[ "$encoding_shape" == *"java.nio.charset.CharsetEncoder"* ]]; then
  printf 'Web encoding objects must remain owner-confined values, not tasks or JVM charset wrappers\n' >&2
  exit 1
fi

encoding_verbose="$(
  javap -classpath "$OUT/core:$OUT/encoding" -verbose \
    io.github.akisarou.jvmwww.web.encoding.TextEncoder \
    io.github.akisarou.jvmwww.web.encoding.TextDecoder \
    io.github.akisarou.jvmwww.web.encoding.Utf8
)"
if grep -Eq \
    'java/nio/charset|java/lang/String.getBytes|CompletableFuture|kotlinx/coroutines|android/os/Handler|java/lang/Runnable' \
    <<<"$encoding_verbose"; then
  printf 'web-encoding must implement exact UTF-8 behavior without JVM charset convenience paths or schedulers\n' >&2
  exit 1
fi
