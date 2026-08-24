#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/base64"

mapfile -d '' CORE_SOURCES < <(
  find "$ROOT/runtime-core/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -d "$OUT/core" "${CORE_SOURCES[@]}"

mapfile -d '' BASE64_SOURCES < <(
  find "$ROOT/web-events/src/main/java" \
       "$ROOT/web-base64/src/main/java" \
       "$ROOT/web-base64-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core" -d "$OUT/base64" "${BASE64_SOURCES[@]}"
BASE64_CP="$OUT/core:$OUT/base64"
java -cp "$BASE64_CP" \
  io.github.akisarou.jvmwww.web.base64.testkit.Base64Conformance
java -cp "$BASE64_CP" \
  io.github.akisarou.jvmwww.web.base64.testkit.Base64WptConformance

base64_shape="$(
  javap -classpath "$BASE64_CP" -p \
    io.github.akisarou.jvmwww.web.base64.Base64Utilities
)"
for required in \
  'public static java.lang.String btoa(java.lang.String)' \
  'public static java.lang.String atob(java.lang.String)'; do
  if [[ "$base64_shape" != *"$required"* ]]; then
    printf 'HTML Base64 JVM boundary is missing: %s\n' "$required" >&2
    exit 1
  fi
done
if [[ "$base64_shape" == *'[]'* ]]; then
  printf 'Base64Utilities must not retain a mutable lookup or normalization array\n' >&2
  exit 1
fi
if compgen -G \
     "$OUT/base64/io/github/akisarou/jvmwww/web/base64/Base64Utilities\$*.class" \
     >/dev/null; then
  printf 'Base64Utilities must not generate inner adapters or operation wrappers\n' >&2
  exit 1
fi

base64_code="$(
  javap -classpath "$BASE64_CP" -p -c \
    io.github.akisarou.jvmwww.web.base64.Base64Utilities
)"
btoa_section="$(
  sed -n '/public static java.lang.String btoa/,/public static java.lang.String atob/p' \
    <<<"$base64_code"
)"
atob_section="$(
  sed -n '/public static java.lang.String atob/,/private static char encodeSixBits/p' \
    <<<"$base64_code"
)"
if [[ "$(grep -c 'newarray       char' <<<"$btoa_section" || true)" -ne 1 ]]; then
  printf 'btoa must allocate exactly one temporary character array\n' >&2
  exit 1
fi
if [[ "$(grep -c 'newarray       char' <<<"$atob_section" || true)" -ne 1 ]]; then
  printf 'atob must allocate exactly one temporary character array\n' >&2
  exit 1
fi
for section_name in btoa atob; do
  section="${btoa_section}"
  if [[ "$section_name" == atob ]]; then section="${atob_section}"; fi
  if grep -Eq 'newarray[[:space:]]+byte|anewarray|java/util/|StringBuilder|StringBuffer|ByteArray|java/io/|java/nio/charset|java/util/regex' \
      <<<"$section"; then
    printf '%s must not allocate byte/object arrays, normalize through builders, or use generic codecs\n' \
      "$section_name" >&2
    exit 1
  fi
done

base64_verbose="$(
  javap -classpath "$BASE64_CP" -verbose \
    io.github.akisarou.jvmwww.web.base64.Base64Utilities
)"
for required in \
  'Base64Utilities.encodeSixBits' \
  'Base64Utilities.decodeSixBits' \
  'Base64Utilities.isAsciiWhitespace' \
  'io/github/akisarou/jvmwww/web/events/DOMException'; do
  if [[ "$base64_verbose" != *"$required"* ]]; then
    printf 'Exact HTML Base64 implementation primitive is missing: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq \
  'java/util/Base64|java/util/regex|java/io/|java/nio/charset|java/lang/String.getBytes|java/lang/StringBuilder|java/lang/StringBuffer|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|LinkedHashMap|TreeMap|Map|List)|java/util/concurrent|java/lang/ref/|java/lang/Runnable|CompletableFuture|kotlinx/coroutines|ExecutorService|ScheduledExecutorService|android/|io/github/akisarou/jvmwww/runtime|java/lang/reflect|java/net/' \
  <<<"$base64_verbose"; then
  printf 'web-base64 must remain exact synchronous primitive string math with no generic codec, scheduler, platform, or runtime dependency\n' >&2
  exit 1
fi
