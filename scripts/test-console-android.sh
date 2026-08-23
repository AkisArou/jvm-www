#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/console" "$OUT/android"

mapfile -d '' CORE_SOURCES < <(
  find "$ROOT/runtime-core/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -d "$OUT/core" "${CORE_SOURCES[@]}"

mapfile -d '' CONSOLE_SOURCES < <(
  find "$ROOT/web-console/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core" -d "$OUT/console" "${CONSOLE_SOURCES[@]}"

mapfile -d '' ANDROID_SOURCES < <(
  find "$ROOT/web-console-android/src/main/java" \
       "$ROOT/web-console-android-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/console" -d "$OUT/android" "${ANDROID_SOURCES[@]}"
java -cp "$OUT/core:$OUT/console:$OUT/android" \
  io.github.akisarou.jvmwww.web.console.android.testkit.AndroidLogConsoleSinkConformance

sink_shape="$(
  javap -classpath "$OUT/core:$OUT/console:$OUT/android" -p \
    io.github.akisarou.jvmwww.web.console.android.AndroidLogConsoleSink
)"
if [[ "$sink_shape" != *"implements io.github.akisarou.jvmwww.web.console.ConsoleSink"* ]] || \
   [[ "$sink_shape" == *"java.lang.Runnable"* ]]; then
  printf 'AndroidLogConsoleSink must implement ConsoleSink directly and never be a Runnable\n' >&2
  exit 1
fi

sink_verbose="$(
  javap -classpath "$OUT/core:$OUT/console:$OUT/android" -verbose \
    io.github.akisarou.jvmwww.web.console.android.AndroidLogConsoleSink
)"
for log_method in d i w e; do
  if [[ "$sink_verbose" != *"android/util/Log.${log_method}:"* ]]; then
    printf 'AndroidLogConsoleSink must map through android.util.Log.%s directly\n' "$log_method" >&2
    exit 1
  fi
done
if grep -Eq \
    'android/util/Log\.println|java/lang/System\.(out|err)|java/util/(Formatter|logging)|android/os/Handler|CompletableFuture|kotlinx/coroutines|ExecutorService|java/lang/Runnable' \
    <<<"$sink_verbose"; then
  printf 'web-console-android must remain a synchronous destination without another scheduler or logging stack\n' >&2
  exit 1
fi
