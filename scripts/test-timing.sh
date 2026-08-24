#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/timing"

mapfile -d '' CORE_SOURCES < <(
  find "$ROOT/runtime-core/src/main/java" "$ROOT/runtime-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -d "$OUT/core" "${CORE_SOURCES[@]}"

mapfile -d '' TIMING_SOURCES < <(
  find "$ROOT/web-timing/src/main/java" "$ROOT/web-timing-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core" -d "$OUT/timing" "${TIMING_SOURCES[@]}"
TIMING_CP="$OUT/core:$OUT/timing"
java -cp "$TIMING_CP" \
  io.github.akisarou.jvmwww.web.timing.testkit.AnimationFrameConformance

scheduler_shape="$(
  javap -classpath "$TIMING_CP" -p \
    io.github.akisarou.jvmwww.web.timing.AnimationFrameScheduler
)"
for required in \
  'implements io.github.akisarou.jvmwww.web.timing.AnimationFrameHost$FrameCallback,io.github.akisarou.jvmwww.runtime.RuntimeOwnedResource' \
  'io.github.akisarou.jvmwww.web.timing.FrameRequestCallback[] callbacks' \
  'long[] generations' \
  'long[] frameSequences' \
  'int[] nextOrder' \
  'int[] previousOrder' \
  'int[] nextFreeSlot' \
  'int runtimeResourceSlot' \
  'double requestAnimationFrame' \
  'void cancelAnimationFrame'; do
  if [[ "$scheduler_shape" != *"$required"* ]]; then
    printf 'AnimationFrameScheduler is missing fused slot/callback boundary: %s\n' \
      "$required" >&2
    exit 1
  fi
done
if [[ "$scheduler_shape" == *'java.lang.Runnable'* ]] || \
   [[ "$scheduler_shape" == *'io.github.akisarou.jvmwww.runtime.RuntimeTask'* ]]; then
  printf 'AnimationFrameScheduler must be the direct host callback, never a Runnable or per-frame RuntimeTask\n' >&2
  exit 1
fi
if compgen -G \
     "$OUT/timing/io/github/akisarou/jvmwww/web/timing/AnimationFrameScheduler\$*.class" \
     >/dev/null; then
  printf 'AnimationFrameScheduler must not allocate inner callback or registration wrappers\n' >&2
  exit 1
fi

host_shape="$(
  javap -classpath "$TIMING_CP" -p \
    io.github.akisarou.jvmwww.web.timing.AnimationFrameHost \
    io.github.akisarou.jvmwww.web.timing.MonotonicClock \
    io.github.akisarou.jvmwww.web.timing.Performance
)"
for required in \
  'interface io.github.akisarou.jvmwww.web.timing.AnimationFrameHost extends io.github.akisarou.jvmwww.web.timing.MonotonicClock' \
  'long nowNanos()' \
  'double now()'; do
  if [[ "$host_shape" != *"$required"* ]]; then
    printf 'Web timing host/Performance boundary is missing: %s\n' "$required" >&2
    exit 1
  fi
done

frame_code="$(
  javap -classpath "$TIMING_CP" -p -c \
    io.github.akisarou.jvmwww.web.timing.AnimationFrameScheduler
)"
on_frame_section="$(
  sed -n '/public void onFrame(long)/,/public void closeForRuntime()/p' <<<"$frame_code"
)"
for required in \
  'RuntimeInstance.enterHostTurn' \
  'RuntimeInstance.leaveHostTurn' \
  'runCallbacks'; do
  if [[ "$on_frame_section" != *"$required"* ]]; then
    printf 'Frame delivery is missing one-turn callback batching primitive: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq 'executeHostTask|admitHostTask|queueMicrotask' <<<"$on_frame_section"; then
  printf 'One display frame must remain one host turn with no checkpoint between callbacks\n' >&2
  exit 1
fi

timing_verbose="$(
  javap -classpath "$TIMING_CP" -verbose \
    io.github.akisarou.jvmwww.web.timing.AnimationFrameScheduler \
    io.github.akisarou.jvmwww.web.timing.AnimationFrameHost \
    io.github.akisarou.jvmwww.web.timing.MonotonicClock \
    io.github.akisarou.jvmwww.web.timing.Performance
)"
for required in \
  'io/github/akisarou/jvmwww/runtime/RuntimeInstance.registerOwnedResource' \
  'io/github/akisarou/jvmwww/runtime/RuntimeInstance.unregisterOwnedResource' \
  'io/github/akisarou/jvmwww/web/timing/AnimationFrameHost.requestFrame' \
  'io/github/akisarou/jvmwww/web/timing/AnimationFrameHost.cancelFrame' \
  'java/lang/System.arraycopy'; do
  if [[ "$timing_verbose" != *"$required"* ]]; then
    printf 'web-timing is missing lifecycle/allocation primitive: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq \
  'java/util/(ArrayList|LinkedList|ArrayDeque|ConcurrentLinkedQueue|HashMap|LinkedHashMap|TreeMap)|java/util/concurrent/atomic|java/lang/ref/WeakReference|java/lang/Runnable|CompletableFuture|kotlinx/coroutines|ScheduledExecutorService|ExecutorService|android/os/Handler|java/time|currentTimeMillis|java/util/regex|java/net/' \
  <<<"$timing_verbose"; then
  printf 'web-timing must avoid generic registries, atomics, wrapper tasks, wall clocks, schedulers, and platform parsers\n' >&2
  exit 1
fi
