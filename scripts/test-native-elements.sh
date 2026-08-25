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
java -cp "$ELEMENT_CP" \
  io.github.akisarou.jvmwww.web.nativeelements.testkit.NativeElementOffsetConformance

shape="$(
  javap -classpath "$ELEMENT_CP" -p \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementOffsetSink \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementPublicInstanceStore \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext \
    io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement
)"
for required in \
  'NativeElementContext implements io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink,io.github.akisarou.jvmwww.web.nativeelements.NativeElementOffsetSink' \
  'NativeElementHost extends io.github.akisarou.jvmwww.web.nativeelements.NativeElementPublicInstanceStore' \
  'private final io.github.akisarou.jvmwww.runtime.RuntimeInstance runtime;' \
  'private final io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost host;' \
  'private double measuredX;' \
  'private double measuredY;' \
  'private double measuredWidth;' \
  'private double measuredHeight;' \
  'private long measuredOffsetParentIdentity;' \
  'private double measuredOffsetTop;' \
  'private double measuredOffsetLeft;' \
  'private boolean measuredHasOffsetParent;' \
  'private byte activeReadKind;' \
  'private boolean readWritten;' \
  'private final io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext context;' \
  'private final long elementIdentity;' \
  'ReactNativeElement getPublicInstance(long)' \
  'ReactNativeElement registerPublicInstance(long, io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement)' \
  'boolean measureBoundingClientRect(long, boolean, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink)' \
  'boolean measureOffset(long, io.github.akisarou.jvmwww.web.nativeelements.NativeElementOffsetSink)' \
  'void setRect(double, double, double, double)' \
  'void setOffset(boolean, long, double, double)' \
  'io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement getOffsetParent()' \
  'double getOffsetTop()' \
  'double getOffsetLeft()' \
  'double getClientWidth()' \
  'double getClientHeight()' \
  'double getClientTop()' \
  'double getClientLeft()' \
  'double getScrollLeft()' \
  'double getScrollTop()' \
  'double getScrollWidth()' \
  'double getScrollHeight()'; do
  if [[ "$shape" != *"$required"* ]]; then
    printf 'Native element primitive/identity boundary is missing: %s\n' "$required" >&2
    exit 1
  fi
done

host_shape="$(
  javap -classpath "$ELEMENT_CP" -p \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost
)"
if [[ "$host_shape" == *'DOMRect'* ]] || [[ "$host_shape" == *'double[]'* ]]; then
  printf 'NativeElementHost must publish multi-value reads through reusable primitive sinks\n' >&2
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

create_section="$(
  sed -n \
    '/ReactNativeElement createElement(long)/,/RuntimeInstance getRuntime()/p' \
    <<<"$context_code"
)"
for required in \
  'NativeElementHost.getPublicInstance' \
  'NativeElementHost.registerPublicInstance'; do
  if [[ "$create_section" != *"$required"* ]]; then
    printf 'Public wrapper creation is missing renderer-owned cache operation: %s\n' "$required" >&2
    exit 1
  fi
done
wrapper_allocations="$(
  grep -c 'new.*class io/github/akisarou/jvmwww/web/nativeelements/ReactNativeElement' \
    <<<"$create_section" || true
)"
if [[ "$wrapper_allocations" -ne 1 ]]; then
  printf 'Public wrapper cache miss must allocate exactly one ReactNativeElement, got %s\n' \
    "$wrapper_allocations" >&2
  exit 1
fi
if grep -Eq 'newarray|anewarray|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|Map|List)|java/lang/(Long|Double)\.valueOf' \
    <<<"$create_section"; then
  printf 'Public wrapper creation must not add a core map, array, or boxed identity\n' >&2
  exit 1
fi

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

offset_dimension_section="$(
  sed -n \
    '/double getOffsetWidth(long)/,/ReactNativeElement getOffsetParent(long)/p' \
    <<<"$context_code"
)"
if grep -Eq 'new[[:space:]]+#|newarray|anewarray|java/util/|java/lang/Double\.valueOf' \
    <<<"$offset_dimension_section"; then
  printf 'offsetWidth/offsetHeight must remain allocation-free primitive reads\n' >&2
  exit 1
fi

offset_position_section="$(
  sed -n \
    '/ReactNativeElement getOffsetParent(long)/,/double getClientWidth(long)/p' \
    <<<"$context_code"
)"
for required in \
  'measureOffset' \
  'NativeElementHost.getPublicInstance' \
  'roundLikeEcmaScript'; do
  if [[ "$offset_position_section" != *"$required"* ]]; then
    printf 'Offset parent/position path is missing direct operation: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq 'new[[:space:]]+#|newarray|anewarray|java/util/|java/lang/(Long|Double)\.valueOf' \
    <<<"$offset_position_section"; then
  printf 'offsetParent/offsetTop/offsetLeft must allocate no result, tuple, or boxed identity\n' >&2
  exit 1
fi

metric_section="$(
  sed -n \
    '/double getClientWidth(long)/,/private boolean measureRect(long, boolean)/p' \
    <<<"$context_code"
)"
for required in \
  'NativeElementHost.getClientWidth' \
  'NativeElementHost.getClientHeight' \
  'NativeElementHost.getClientTop' \
  'NativeElementHost.getClientLeft' \
  'NativeElementHost.getScrollLeft' \
  'NativeElementHost.getScrollTop' \
  'NativeElementHost.getScrollWidth' \
  'NativeElementHost.getScrollHeight'; do
  if [[ "$metric_section" != *"$required"* ]]; then
    printf 'Native element scalar metric path is missing direct host call: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq 'new[[:space:]]+#|newarray|anewarray|java/util/|java/lang/Double\.valueOf' \
    <<<"$metric_section"; then
  printf 'client and scroll metric reads must remain allocation-free scalar host calls\n' >&2
  exit 1
fi

rect_read_section="$(
  sed -n \
    '/private boolean measureRect(long, boolean)/,/private boolean measureOffset(long)/p' \
    <<<"$context_code"
)"
offset_read_section="$(
  sed -n \
    '/private boolean measureOffset(long)/,/private void beginRead(byte)/p' \
    <<<"$context_code"
)"
if [[ "$rect_read_section" != *'NativeElementHost.measureBoundingClientRect'* ]]; then
  printf 'NativeElementContext must call the rectangle host directly\n' >&2
  exit 1
fi
if [[ "$offset_read_section" != *'NativeElementHost.measureOffset'* ]]; then
  printf 'NativeElementContext must call the offset host directly\n' >&2
  exit 1
fi
for section in "$rect_read_section" "$offset_read_section"; do
  if grep -Eq 'newarray|anewarray|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|Map|List)|java/lang/(Long|Double)\.valueOf' \
      <<<"$section"; then
    printf 'Reusable native element measurement must not allocate a tuple, collection, or boxed value\n' >&2
    exit 1
  fi
done

verbose="$(
  javap -classpath "$ELEMENT_CP" -verbose \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementOffsetSink \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementPublicInstanceStore \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementRuntimeChecks \
    io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement
)"
if [[ "$verbose" != *'java/lang/Math.floor'* ]]; then
  printf 'offset dimensions and positions must use the explicit ECMAScript rounding kernel\n' >&2
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
