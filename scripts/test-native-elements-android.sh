#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/geometry" "$OUT/native-elements" "$OUT/android-elements"

mapfile -d '' CORE_SOURCES < <(
  find "$ROOT/runtime-core/src/main/java" "$ROOT/runtime-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -d "$OUT/core" "${CORE_SOURCES[@]}"

mapfile -d '' GEOMETRY_SOURCES < <(
  find "$ROOT/web-geometry/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core" -d "$OUT/geometry" "${GEOMETRY_SOURCES[@]}"

mapfile -d '' ELEMENT_SOURCES < <(
  find "$ROOT/web-native-elements/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/geometry" -d "$OUT/native-elements" "${ELEMENT_SOURCES[@]}"

mapfile -d '' ANDROID_ELEMENT_SOURCES < <(
  find "$ROOT/web-native-elements-android/src/main/java" \
       "$ROOT/web-native-elements-android-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/geometry:$OUT/native-elements" \
  -d "$OUT/android-elements" "${ANDROID_ELEMENT_SOURCES[@]}"
ELEMENT_CP="$OUT/core:$OUT/geometry:$OUT/native-elements:$OUT/android-elements"
java -cp "$ELEMENT_CP" \
  io.github.akisarou.jvmwww.web.nativeelements.android.testkit.AndroidNativeElementHostConformance
java -cp "$ELEMENT_CP" \
  io.github.akisarou.jvmwww.web.nativeelements.android.testkit.AndroidNativeElementIntegrationConformance

host_shape="$(
  javap -classpath "$ELEMENT_CP" -p \
    io.github.akisarou.jvmwww.web.nativeelements.android.AndroidNativeElementHost
)"
for required in \
  'implements io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost,java.lang.AutoCloseable' \
  'private static final byte STATE_HAS_CLIENT_AND_SCROLL;' \
  'private final android.os.Looper looper;' \
  'private long[] generations;' \
  'private int[] nextFreeSlot;' \
  'private byte[] states;' \
  'private java.lang.String[] metadata;' \
  'private double[] layout;' \
  'private int firstFreeSlot;' \
  'private int nextUnusedSlot;' \
  'private int activeCount;' \
  'private boolean closed;' \
  'long mountElement(java.lang.String, java.lang.String)' \
  'boolean commitMetadata(long, java.lang.String, java.lang.String)' \
  'boolean commitLayout(long, double, double, double, double, double, double, double, double)' \
  'boolean commitClientAndScrollMetrics(long, double, double, double, double, double, double, double, double)' \
  'boolean clearCommittedLayout(long)' \
  'boolean clearCommittedClientAndScrollMetrics(long)' \
  'boolean unmountElement(long)' \
  'boolean measureBoundingClientRect(long, boolean, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink)' \
  'double getClientWidth(long)' \
  'double getClientHeight(long)' \
  'double getClientTop(long)' \
  'double getClientLeft(long)' \
  'double getScrollLeft(long)' \
  'double getScrollTop(long)' \
  'double getScrollWidth(long)' \
  'double getScrollHeight(long)' \
  'private double readClientAndScrollMetric(long, int)'; do
  if [[ "$host_shape" != *"$required"* ]]; then
    printf 'Android native element host boundary is missing: %s\n' "$required" >&2
    exit 1
  fi
done

if compgen -G \
     "$OUT/android-elements/io/github/akisarou/jvmwww/web/nativeelements/android/AndroidNativeElementHost\$*.class" \
     >/dev/null; then
  printf 'Android native element host must not generate per-element or per-measurement wrappers\n' >&2
  exit 1
fi

host_code="$(
  javap -classpath "$ELEMENT_CP" -p -c \
    io.github.akisarou.jvmwww.web.nativeelements.android.AndroidNativeElementHost
)"
measure_section="$(
  sed -n \
    '/public boolean measureBoundingClientRect(long, boolean, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink)/,/public double getClientWidth(long)/p' \
    <<<"$host_code"
)"
if [[ "$measure_section" != *'NativeElementRectSink.setRect'* ]]; then
  printf 'Android rectangle measurement must publish directly through the caller reusable sink\n' >&2
  exit 1
fi
if grep -Eq \
  'new[[:space:]]+#|newarray|anewarray|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|Map|List)|java/lang/(Double|Long)\.valueOf' \
  <<<"$measure_section"; then
  printf 'Android rectangle measurement must allocate no result, tuple, collection, or boxed coordinate\n' >&2
  exit 1
fi

metric_getter_section="$(
  sed -n \
    '/public double getClientWidth(long)/,/public void close()/p' \
    <<<"$host_code"
)"
if grep -Eq \
  'new[[:space:]]+#|newarray|anewarray|java/util/|java/lang/(Double|Long)\.valueOf' \
  <<<"$metric_getter_section"; then
  printf 'Android client and scroll getters must remain allocation-free scalar reads\n' >&2
  exit 1
fi

metric_read_section="$(
  sed -n \
    '/private double readClientAndScrollMetric(long, int)/,/private int allocateSlot()/p' \
    <<<"$host_code"
)"
if [[ "$metric_read_section" != *'daload'* ]]; then
  printf 'Android client and scroll metrics must read directly from the committed primitive table\n' >&2
  exit 1
fi
if grep -Eq \
  'new[[:space:]]+#|newarray|anewarray|java/util/|java/lang/(Double|Long)\.valueOf' \
  <<<"$metric_read_section"; then
  printf 'Android metric resolution must allocate no result, tuple, collection, or boxed coordinate\n' >&2
  exit 1
fi

layout_commit_section="$(
  sed -n \
    '/public boolean commitLayout(long, double, double, double, double, double, double, double, double)/,/public boolean commitClientAndScrollMetrics/p' \
    <<<"$host_code"
)"
metrics_commit_section="$(
  sed -n \
    '/public boolean commitClientAndScrollMetrics(long, double, double, double, double, double, double, double, double)/,/public boolean clearCommittedLayout(long)/p' \
    <<<"$host_code"
)"
for section in "$layout_commit_section" "$metrics_commit_section"; do
  if grep -Eq \
    'new[[:space:]]+#|newarray|anewarray|java/util/|java/lang/(Double|Long)\.valueOf' \
    <<<"$section"; then
    printf 'Committed layout and metric publication must remain allocation-free primitive array updates\n' >&2
    exit 1
  fi
done

mount_section="$(
  sed -n \
    '/public long mountElement(java.lang.String, java.lang.String)/,/public boolean commitMetadata/p' \
    <<<"$host_code"
)"
if grep -Eq \
  'new[[:space:]]+#|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|Map|List)|java/lang/(Long|Integer)\.valueOf' \
  <<<"$mount_section"; then
  printf 'Mounting must not allocate a host-side element node, map entry, or boxed identity\n' >&2
  exit 1
fi

host_verbose="$(
  javap -classpath "$ELEMENT_CP" -verbose \
    io.github.akisarou.jvmwww.web.nativeelements.android.AndroidNativeElementHost
)"
for required in \
  'android/os/Looper.myLooper' \
  'java/lang/System.arraycopy' \
  'NativeElementRectSink.setRect'; do
  if [[ "$host_verbose" != *"$required"* ]]; then
    printf 'Android native element host is missing direct primitive/owner operation: %s\n' \
      "$required" >&2
    exit 1
  fi
done
if grep -Eq \
  'android/view/|android/graphics/|android/os/Handler|android/util/(SparseArray|LongSparseArray)|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|LinkedHashMap|TreeMap|Map|List)|java/util/concurrent|java/util/regex|java/lang/ref/|java/lang/Runnable|CompletableFuture|kotlinx/coroutines|ExecutorService|ScheduledExecutorService|java/lang/reflect|java/net/' \
  <<<"$host_verbose"; then
  printf 'Android native element host must retain only committed primitive snapshots, not Views, maps, schedulers, or reflection paths\n' >&2
  exit 1
fi
if grep -Eq 'monitorenter|monitorexit' <<<"$host_code"; then
  printf 'Android native element host is Looper-confined and must not add locking\n' >&2
  exit 1
fi
