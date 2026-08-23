#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/console"

mapfile -d '' CORE_SOURCES < <(
  find "$ROOT/runtime-core/src/main/java" "$ROOT/runtime-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -d "$OUT/core" "${CORE_SOURCES[@]}"

mapfile -d '' CONSOLE_SOURCES < <(
  find "$ROOT/web-console/src/main/java" "$ROOT/web-console-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core" -d "$OUT/console" "${CONSOLE_SOURCES[@]}"
java -cp "$OUT/core:$OUT/console" \
  io.github.akisarou.jvmwww.web.console.testkit.ConsoleConformance

console_shape="$(
  javap -classpath "$OUT/core:$OUT/console" -p \
    io.github.akisarou.jvmwww.web.console.Console \
    io.github.akisarou.jvmwww.web.console.ConsoleSink
)"
if [[ "$console_shape" == *"io.github.akisarou.jvmwww.runtime.RuntimeTask"* ]] || \
   [[ "$console_shape" == *"java.lang.Runnable"* ]] || \
   grep -Eq 'static .*\$CountEntry|static .*\$TimerEntry' <<<"$console_shape"; then
  printf 'Console must remain an owner-confined value with per-instance count and timer state\n' >&2
  exit 1
fi

console_verbose="$(
  javap -classpath "$OUT/core:$OUT/console" -verbose \
    io.github.akisarou.jvmwww.web.console.Console \
    io.github.akisarou.jvmwww.web.console.ConsoleClock \
    io.github.akisarou.jvmwww.web.console.ConsoleSink \
    io.github.akisarou.jvmwww.web.console.DefaultConsoleValueFormatter
)"
if grep -Eq \
    'java/util/(HashMap|LinkedHashMap|ConcurrentHashMap)|java/util/Formatter|CompletableFuture|kotlinx/coroutines|ScheduledExecutorService|ExecutorService|android/os/Handler|android/util/Log|java/lang/Runnable' \
    <<<"$console_verbose"; then
  printf 'web-console must not introduce global maps, formatters, schedulers, Android logging, or Runnable jobs\n' >&2
  exit 1
fi
