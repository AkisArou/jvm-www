#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
CORE_OUT="$OUT/core"
ANDROID_OUT="$OUT/android"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$CORE_OUT" "$ANDROID_OUT"

mapfile -d '' CORE_SOURCES < <(
  find \
    "$ROOT/runtime-core/src/main/java" \
    "$ROOT/runtime-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)

javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -d "$CORE_OUT" "${CORE_SOURCES[@]}"
java -cp "$CORE_OUT" io.github.akisarou.jvmwww.testkit.RuntimeInstanceConformance
java -cp "$CORE_OUT" io.github.akisarou.jvmwww.testkit.PromiseConformance
java -cp "$CORE_OUT" io.github.akisarou.jvmwww.testkit.AsyncFrameConformance
java -cp "$CORE_OUT" io.github.akisarou.jvmwww.testkit.TimerConformance
java -cp "$CORE_OUT" io.github.akisarou.jvmwww.testkit.IntervalConformance
java -cp "$CORE_OUT" io.github.akisarou.jvmwww.testkit.PlatformPromiseConformance

mapfile -d '' ANDROID_SOURCES < <(
  find \
    "$ROOT/runtime-android/src/main/java" \
    "$ROOT/runtime-android-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)

# The testkit supplies tiny deterministic android.os stubs. They compile the real adapter without
# placing those stubs in runtime-android's production artifact.
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$CORE_OUT" -d "$ANDROID_OUT" "${ANDROID_SOURCES[@]}"
java -cp "$CORE_OUT:$ANDROID_OUT" \
  io.github.akisarou.jvmwww.androidtestkit.HandlerRuntimeHostConformance

promise_job_shape="$(
  javap -classpath "$CORE_OUT" -p \
    'io.github.akisarou.jvmwww.runtime.JsPromise$PromiseJob'
)"
if [[ "$promise_job_shape" != *"extends io.github.akisarou.jvmwww.runtime.RuntimeTask"* ]] || \
   [[ "$promise_job_shape" == *"java.lang.Runnable"* ]]; then
  printf 'Promise jobs must be RuntimeTask values, never one Runnable per reaction\n' >&2
  exit 1
fi

async_frame_shape="$(
  javap -classpath "$CORE_OUT" -p io.github.akisarou.jvmwww.runtime.AsyncFrame
)"
if [[ "$async_frame_shape" != *"extends io.github.akisarou.jvmwww.runtime.JsPromise"* ]] || \
   [[ "$async_frame_shape" != *"implements io.github.akisarou.jvmwww.runtime.JsPromise\$PromiseJob"* ]] || \
   [[ "$async_frame_shape" == *"java.lang.Runnable"* ]]; then
  printf 'AsyncFrame must be the result Promise and intrusive runtime job, never a Runnable\n' >&2
  exit 1
fi

platform_promise_shape="$(
  javap -classpath "$CORE_OUT" -p io.github.akisarou.jvmwww.runtime.PlatformPromise
)"
if [[ "$platform_promise_shape" != *"extends io.github.akisarou.jvmwww.runtime.JsPromise implements io.github.akisarou.jvmwww.runtime.RuntimeTask"* ]] || \
   [[ "$platform_promise_shape" == *"java.lang.Runnable"* ]]; then
  printf 'PlatformPromise must be the returned Promise and admitted RuntimeTask, never a Runnable\n' >&2
  exit 1
fi
if compgen -G \
     "$CORE_OUT/io/github/akisarou/jvmwww/runtime/PlatformPromise\$*.class" \
     >/dev/null; then
  printf 'PlatformPromise must not allocate adapter-specific inner completion wrappers\n' >&2
  exit 1
fi

timer_queue_shape="$(
  javap -classpath "$CORE_OUT" -p io.github.akisarou.jvmwww.runtime.RuntimeTimerQueue
)"
timer_runnable_count="$(grep -c 'java.lang.Runnable' <<<"$timer_queue_shape" || true)"
if [[ "$timer_runnable_count" -ne 1 ]]; then
  printf 'RuntimeTimerQueue must own exactly one reusable host Runnable, got %s\n' \
    "$timer_runnable_count" >&2
  exit 1
fi

timer_entry_shape="$(
  javap -classpath "$CORE_OUT" -p \
    'io.github.akisarou.jvmwww.runtime.RuntimeTimerQueue$TimerEntry'
)"
if [[ "$timer_entry_shape" == *"java.lang.Runnable"* ]]; then
  printf 'Logical timer entries must not become one Runnable per timer\n' >&2
  exit 1
fi

core_verbose="$(
  javap -classpath "$CORE_OUT" -verbose \
    io.github.akisarou.jvmwww.runtime.JsPromise \
    io.github.akisarou.jvmwww.runtime.AsyncFrame \
    io.github.akisarou.jvmwww.runtime.PlatformPromise \
    io.github.akisarou.jvmwww.runtime.RuntimeTimerQueue
)"
if grep -Eq \
    'CompletableFuture|kotlinx/coroutines|ScheduledExecutorService|ScheduledThreadPoolExecutor|java/util/TimerTask|java/util/Timer' \
    <<<"$core_verbose"; then
  printf 'Forbidden future/coroutine/platform-timer scheduler dependency in runtime-core\n' >&2
  exit 1
fi
if grep -Eq \
    'java/util/concurrent/atomic/AtomicInteger([^A-Za-z]|$)|AtomicIntegerFieldUpdater|AtomicReferenceFieldUpdater' \
    <<<"$(javap -classpath "$CORE_OUT" -verbose io.github.akisarou.jvmwww.runtime.PlatformPromise)"; then
  printf 'PlatformPromise must not allocate an atomic holder or depend on reflective field updaters\n' >&2
  exit 1
fi

android_host_shape="$(
  javap -classpath "$CORE_OUT:$ANDROID_OUT" -p \
    io.github.akisarou.jvmwww.runtime.android.HandlerRuntimeHost
)"
if [[ "$android_host_shape" != *"implements io.github.akisarou.jvmwww.runtime.OwnerExecutor,io.github.akisarou.jvmwww.runtime.TimerHost"* ]]; then
  printf 'HandlerRuntimeHost must be the shared OwnerExecutor and TimerHost\n' >&2
  exit 1
fi

if compgen -G \
     "$ANDROID_OUT/io/github/akisarou/jvmwww/runtime/android/HandlerRuntimeHost\$*.class" \
     >/dev/null; then
  printf 'HandlerRuntimeHost must pass reusable runtime callbacks directly, without wrappers\n' >&2
  exit 1
fi

android_host_verbose="$(
  javap -classpath "$CORE_OUT:$ANDROID_OUT" -verbose \
    io.github.akisarou.jvmwww.runtime.android.HandlerRuntimeHost
)"
for required in \
  'android/os/SystemClock.uptimeMillis' \
  'android/os/Handler.post:' \
  'android/os/Handler.postAtTime' \
  'android/os/Handler.removeCallbacks'; do
  if [[ "$android_host_verbose" != *"$required"* ]]; then
    printf 'HandlerRuntimeHost is missing required Android primitive: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq \
    'postDelayed|java/lang/System\.(nanoTime|currentTimeMillis)|ScheduledExecutorService|ScheduledThreadPoolExecutor|java/util/TimerTask|java/util/Timer' \
    <<<"$android_host_verbose"; then
  printf 'Android host must use absolute Handler uptime, not another scheduler or clock\n' >&2
  exit 1
fi

if command -v node >/dev/null 2>&1; then
  reference_trace="$(node "$ROOT/conformance/reference/promise-ordering.mjs")"
  expected_trace="sync,end,microtask,promise,nested"
  if [[ "$reference_trace" != "$expected_trace" ]]; then
    printf 'Node Promise ordering drift: expected %s, got %s\n' \
      "$expected_trace" "$reference_trace" >&2
    exit 1
  fi
fi
