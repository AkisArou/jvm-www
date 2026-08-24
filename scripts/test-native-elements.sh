#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/geometry" "$OUT/native-elements"

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
  find "$ROOT/web-native-elements/src/main/java" \
       "$ROOT/web-native-elements-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/geometry" -d "$OUT/native-elements" "${ELEMENT_SOURCES[@]}"
ELEMENT_CP="$OUT/core:$OUT/geometry:$OUT/native-elements"
java -cp "$ELEMENT_CP" \
  io.github.akisarou.jvmwww.web.nativeelements.testkit.NativeElementConformance

shape="$(
  javap -classpath "$ELEMENT_CP" -p \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext \
    io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement
)"
for required in \
  'NativeElementContext implements io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink' \
  'private final io.github.akisarou.jvmwww.runtime.RuntimeInstance runtime;' \
  'private final io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost host;' \
  'private double measuredX;' \
  'private double measuredY;' \
  'private double measuredWidth;' \
  'private double measuredHeight;' \
  'private boolean measurementActive;' \
  'private boolean measurementWritten;' \
  'private final io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext context;' \
  'private final long elementIdentity;' \
  'boolean measureBoundingClientRect(long, boolean, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink)' \
  'void setRect(double, double, double, double)' \
  'io.github.akisarou.jvmwww.web.geometry.DOMRect getBoundingClientRect()' \
  'double getOffsetWidth()' \
  'double getOffsetHeight()'; do
  if [[ "$shape" != *"$required"* ]]; then
    printf 'Native element primitive/sink boundary is missing: %s\n' "$required" >&2
    exit 1
  fi
done

host_shape="$(
  javap -classpath "$ELEMENT_CP" -p \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost
)"
if [[ "$host_shape" == *'DOMRect'* ]]; then
  printf 'NativeElementHost must publish primitive rectangles through the reusable sink\n' >&2
  exit 1
fi

for class_name in NativeElementContext ReactNativeElement; do
  if compgen -G \
       "$OUT/native-elements/io/github/akisarou/jvmwww/web/nativeelements/${class_name}\$*.class" \
       >/dev/null; then
    printf '%s must not generate per-call adapters or wrapper classes\n' "$class_name" >&2
    exit 1
  fi
done

context_code="$(
  javap -classpath "$ELEMENT_CP" -p -c \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext
)"
bounds_section="$(
  sed -n \
    '/DOMRect getBoundingClientRect(long)/,/double getOffsetWidth(long)/p' \
    <<<"$context_code"
)"
rect_allocations="$(
  grep -c 'class io/github/akisarou/jvmwww/web/geometry/DOMRect' \
    <<<"$bounds_section" || true
)"
if [[ "$rect_allocations" -ne 1 ]]; then
  printf 'getBoundingClientRect must allocate exactly one static DOMRect result, got %s\n' \
    "$rect_allocations" >&2
  exit 1
fi
if grep -Eq 'newarray|anewarray|java/util/|java/lang/Double\.valueOf' \
    <<<"$bounds_section"; then
  printf 'getBoundingClientRect must not allocate tuples, arrays, collections, or boxed numbers\n' >&2
  exit 1
fi

offset_section="$(
  sed -n \
    '/double getOffsetWidth(long)/,/private boolean measure(long, boolean)/p' \
    <<<"$context_code"
)"
if grep -Eq 'new[[:space:]]+#|newarray|anewarray|java/util/|java/lang/Double\.valueOf' \
    <<<"$offset_section"; then
  printf 'offsetWidth/offsetHeight must remain allocation-free primitive reads\n' >&2
  exit 1
fi

measure_section="$(
  sed -n \
    '/private boolean measure(long, boolean)/,/void assertAccess()/p' \
    <<<"$context_code"
)"
if [[ "$measure_section" != *'NativeElementHost.measureBoundingClientRect'* ]]; then
  printf 'NativeElementContext must call the synchronous primitive measurement host directly\n' >&2
  exit 1
fi
if grep -Eq 'newarray|anewarray|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|Map|List)|java/lang/Double\.valueOf' \
    <<<"$measure_section"; then
  printf 'Measurement publication must not allocate a tuple, collection, or boxed coordinate\n' >&2
  exit 1
fi

verbose="$(
  javap -classpath "$ELEMENT_CP" -verbose \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementRuntimeChecks \
    io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement
)"
if [[ "$verbose" != *'java/lang/Math.floor'* ]]; then
  printf 'offset dimensions must use the explicit ECMAScript rounding kernel\n' >&2
  exit 1
fi
if [[ "$verbose" == *'java/lang/Math.round'* ]]; then
  printf 'Java Math.round loses ECMAScript negative-zero behavior\n' >&2
  exit 1
fi
if grep -Eq \
  'android/|androidx/|java/awt/|io/github/akisarou/jvmwww/runtime/(RuntimeTask|JsPromise)|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|LinkedHashMap|TreeMap|Map|List)|java/util/concurrent|java/lang/ref/|java/lang/Runnable|CompletableFuture|kotlinx/coroutines|ExecutorService|ScheduledExecutorService|java/lang/reflect|java/net/|\[D' \
  <<<"$verbose"; then
  printf 'web-native-elements must remain a primitive owner-confined renderer boundary with no platform, collection, scheduler, or coordinate-array dependency\n' >&2
  exit 1
fi
