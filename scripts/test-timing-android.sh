#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/timing" "$OUT/android"

mapfile -d '' CORE_SOURCES < <(
  find "$ROOT/runtime-core/src/main/java" "$ROOT/runtime-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -d "$OUT/core" "${CORE_SOURCES[@]}"

mapfile -d '' TIMING_SOURCES < <(
  find "$ROOT/web-timing/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core" -d "$OUT/timing" "${TIMING_SOURCES[@]}"

mapfile -d '' ANDROID_SOURCES < <(
  find "$ROOT/web-timing-android/src/main/java" \
       "$ROOT/web-timing-android-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/timing" -d "$OUT/android" "${ANDROID_SOURCES[@]}"
TIMING_ANDROID_CP="$OUT/core:$OUT/timing:$OUT/android"
java -cp "$TIMING_ANDROID_CP" \
  io.github.akisarou.jvmwww.web.timing.android.testkit.ChoreographerAnimationFrameHostConformance

host_shape="$(
  javap -classpath "$TIMING_ANDROID_CP" -p \
    io.github.akisarou.jvmwww.web.timing.android.ChoreographerAnimationFrameHost
)"
for required in \
  'implements io.github.akisarou.jvmwww.web.timing.AnimationFrameHost' \
  'android.view.Choreographer$FrameCallback' \
  'private final android.os.Looper looper;' \
  'private final android.view.Choreographer choreographer;' \
  'private io.github.akisarou.jvmwww.web.timing.AnimationFrameHost$FrameCallback pendingCallback;'; do
  if [[ "$host_shape" != *"$required"* ]]; then
    printf 'ChoreographerAnimationFrameHost is missing fused host state: %s\n' "$required" >&2
    exit 1
  fi
done
if [[ "$host_shape" == *"java.lang.Runnable"* ]]; then
  printf 'ChoreographerAnimationFrameHost must be the Android callback, never a Runnable wrapper\n' >&2
  exit 1
fi
if compgen -G \
     "$OUT/android/io/github/akisarou/jvmwww/web/timing/android/ChoreographerAnimationFrameHost\$*.class" \
     >/dev/null; then
  printf 'ChoreographerAnimationFrameHost must not allocate inner frame adapters\n' >&2
  exit 1
fi

host_verbose="$(
  javap -classpath "$TIMING_ANDROID_CP" -verbose \
    io.github.akisarou.jvmwww.web.timing.android.ChoreographerAnimationFrameHost
)"
for required in \
  'android/os/Looper.myLooper' \
  'android/view/Choreographer.getInstance' \
  'android/view/Choreographer.postFrameCallback' \
  'android/view/Choreographer.removeFrameCallback' \
  'java/lang/System.nanoTime' \
  'io/github/akisarou/jvmwww/web/timing/AnimationFrameHost$FrameCallback.onFrame'; do
  if [[ "$host_verbose" != *"$required"* ]]; then
    printf 'Android timing host is missing direct platform primitive: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq \
    'android/os/(Handler|SystemClock|Build)|postFrameCallbackDelayed|VsyncCallback|java/lang/System\.currentTimeMillis|java/lang/Runnable|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|TreeMap|Map)|java/util/concurrent|CompletableFuture|kotlinx/coroutines|ExecutorService|ScheduledExecutorService|java/lang/reflect' \
    <<<"$host_verbose"; then
  printf 'web-timing-android must use one direct Choreographer callback without another queue, scheduler, clock, or compatibility branch\n' >&2
  exit 1
fi
