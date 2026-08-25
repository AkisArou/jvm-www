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
  find "$ROOT/web-native-elements/src/main/java" \
       "$ROOT/web-native-elements-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
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
  io.github.akisarou.jvmwww.web.nativeelements.testkit.NativeElementCollectionConformance
java -cp "$ELEMENT_CP" \
  io.github.akisarou.jvmwww.web.nativeelements.android.testkit.AndroidNativeElementCollectionConformance

shape="$(
  javap -classpath "$ELEMENT_CP" -p \
    io.github.akisarou.jvmwww.web.nativeelements.HTMLCollection \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext \
    io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement
)"
for required in \
  'private final io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext context;' \
  'private final long parentIdentity;' \
  'int getLength()' \
  'io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement item(double)' \
  'io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement namedItem(java.lang.String)' \
  'private io.github.akisarou.jvmwww.web.nativeelements.HTMLCollection children;' \
  'io.github.akisarou.jvmwww.web.nativeelements.HTMLCollection getChildren()' \
  'io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement getElementChildAt(long, int)' \
  'io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement getNamedElementChild(long, java.lang.String)'; do
  if [[ "$shape" != *"$required"* ]]; then
    printf 'Native element live collection boundary is missing: %s\n' "$required" >&2
    exit 1
  fi
done

wrapper_shape="$(
  javap -classpath "$ELEMENT_CP" -p \
    io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement
)"
wrapper_field_count="$(grep -c '^  private ' <<<"$wrapper_shape" || true)"
if [[ "$wrapper_field_count" -ne 3 ]]; then
  printf 'ReactNativeElement must remain context + identity + one lazy children reference, got %s fields\n' \
    "$wrapper_field_count" >&2
  exit 1
fi

for class_name in HTMLCollection ReactNativeElement; do
  if compgen -G \
       "$OUT/native-elements/io/github/akisarou/jvmwww/web/nativeelements/${class_name}\$*.class" \
       >/dev/null; then
    printf '%s must not generate iterator, adapter, or collection wrapper inner classes\n' \
      "$class_name" >&2
    exit 1
  fi
done

element_code="$(
  javap -classpath "$ELEMENT_CP" -p -c \
    io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement
)"
children_getter="$(
  sed -n \
    '/public io.github.akisarou.jvmwww.web.nativeelements.HTMLCollection getChildren()/,/public io.github.akisarou.jvmwww.web.geometry.DOMRect getBoundingClientRect()/p' \
    <<<"$element_code"
)"
collection_allocations="$(
  grep -c 'new.*class io/github/akisarou/jvmwww/web/nativeelements/HTMLCollection' \
    <<<"$children_getter" || true
)"
if [[ "$collection_allocations" -ne 1 ]]; then
  printf 'children must lazily allocate exactly one same-object HTMLCollection, got %s\n' \
    "$collection_allocations" >&2
  exit 1
fi
if grep -Eq 'newarray|anewarray|java/util/|java/lang/(Long|Integer|Double)\.valueOf' \
    <<<"$children_getter"; then
  printf 'children getter must not allocate snapshots, maps, arrays, or boxed state\n' >&2
  exit 1
fi

collection_code="$(
  javap -classpath "$ELEMENT_CP" -p -c \
    io.github.akisarou.jvmwww.web.nativeelements.HTMLCollection
)"
for required in \
  'NativeElementContext.getChildElementCount' \
  'NativeElementContext.getElementChildAt' \
  'NativeElementContext.getNamedElementChild' \
  'java/lang/Math.ceil' \
  'java/lang/Math.floor'; do
  if [[ "$collection_code" != *"$required"* ]]; then
    printf 'HTMLCollection is missing direct live/index conversion operation: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq \
  'new[[:space:]]+#|newarray|anewarray|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|Map|List|Iterator)|java/lang/(Long|Integer|Double)\.valueOf' \
  <<<"$collection_code"; then
  printf 'HTMLCollection reads must retain no snapshot and allocate no iterator, array, map, or box\n' >&2
  exit 1
fi

context_code="$(
  javap -classpath "$ELEMENT_CP" -p -c \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext
)"
collection_read_section="$(
  sed -n \
    '/ReactNativeElement getElementChildAt(long, int)/,/private io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement getRelatedElement(long, byte)/p' \
    <<<"$context_code"
)"
for required in \
  'getChildElementCount' \
  'readRelatedElementIdentity' \
  'NativeElementHost.getId' \
  'createElement'; do
  if [[ "$collection_read_section" != *"$required"* ]]; then
    printf 'Live collection traversal is missing direct helper/scalar operation: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq \
  'new[[:space:]]+#|newarray|anewarray|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|Map|List)|java/lang/(Long|Integer|Double)\.valueOf' \
  <<<"$collection_read_section"; then
  printf 'Collection traversal may allocate only the final requested public wrapper on cache miss\n' >&2
  exit 1
fi

verbose="$(
  javap -classpath "$ELEMENT_CP" -verbose \
    io.github.akisarou.jvmwww.web.nativeelements.HTMLCollection \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext \
    io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement
)"
if grep -Eq \
  'android/|androidx/|java/awt/|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|LinkedHashMap|TreeMap|Map|List|Iterator)|java/util/concurrent|java/lang/ref/|java/lang/Runnable|CompletableFuture|kotlinx/coroutines|ExecutorService|ScheduledExecutorService|java/lang/reflect|java/net/' \
  <<<"$verbose"; then
  printf 'Live native element collections must not add platform objects, generic snapshots, schedulers, or reflection\n' >&2
  exit 1
fi
