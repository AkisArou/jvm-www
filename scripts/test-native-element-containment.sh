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
timeout 15s java -cp "$ELEMENT_CP" \
  io.github.akisarou.jvmwww.web.nativeelements.testkit.NativeElementContainmentConformance
timeout 15s java -cp "$ELEMENT_CP" \
  io.github.akisarou.jvmwww.web.nativeelements.android.testkit.AndroidNativeElementContainmentConformance

shape="$(
  javap -classpath "$ELEMENT_CP" -p \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext \
    io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement
)"
for required in \
  'boolean contains(long, io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext, long)' \
  'boolean isSameNode(io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement)' \
  'boolean contains(io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement)' \
  'private boolean readRelatedElementIdentity(long, byte)'; do
  if [[ "$shape" != *"$required"* ]]; then
    printf 'Native element containment boundary is missing: %s\n' "$required" >&2
    exit 1
  fi
done

wrapper_shape="$(
  javap -classpath "$ELEMENT_CP" -p \
    io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement
)"
wrapper_field_count="$(grep -c '^  private ' <<<"$wrapper_shape" || true)"
if [[ "$wrapper_field_count" -ne 3 ]]; then
  printf 'Containment must not add per-element state; expected 3 private wrapper fields, got %s\n' \
    "$wrapper_field_count" >&2
  exit 1
fi

for class_name in NativeElementContext ReactNativeElement; do
  if compgen -G \
       "$OUT/native-elements/io/github/akisarou/jvmwww/web/nativeelements/${class_name}\$*.class" \
       >/dev/null; then
    printf '%s must not generate an ancestry iterator or adapter class\n' "$class_name" >&2
    exit 1
  fi
done

context_code="$(
  javap -classpath "$ELEMENT_CP" -p -c \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext
)"
contains_section="$(
  sed -n \
    '/boolean contains(long, io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext, long)/,/io.github.akisarou.jvmwww.web.geometry.DOMRect getBoundingClientRect(long)/p' \
    <<<"$context_code"
)"
for required in \
  'assertAccess' \
  'readRelatedElementIdentity' \
  'measuredRelatedElementIdentity' \
  'lshl'; do
  if [[ "$contains_section" != *"$required"* ]]; then
    printf 'Primitive containment walk is missing: %s\n' "$required" >&2
    exit 1
  fi
done
if [[ "$contains_section" == *'createElement'* ]] || \
   [[ "$contains_section" == *'getParentElement'* ]]; then
  printf 'Containment must walk primitive identities, not public wrappers\n' >&2
  exit 1
fi
if grep -Eq \
  'new[[:space:]]+#|newarray|anewarray|java/util/|java/lang/(Long|Integer|Double)\.valueOf' \
  <<<"$contains_section"; then
  printf 'Containment must allocate no ancestor set, iterator, array, wrapper, or box\n' >&2
  exit 1
fi

element_code="$(
  javap -classpath "$ELEMENT_CP" -p -c \
    io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement
)"
identity_section="$(
  sed -n \
    '/public boolean isSameNode(io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement)/,/public io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement getParentElement()/p' \
    <<<"$element_code"
)"
for required in \
  'NativeElementContext.assertAccess' \
  'NativeElementContext.contains'; do
  if [[ "$identity_section" != *"$required"* ]]; then
    printf 'Public identity/containment method is missing direct operation: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq \
  'new[[:space:]]+#|newarray|anewarray|java/util/|java/lang/(Long|Integer|Double)\.valueOf' \
  <<<"$identity_section"; then
  printf 'Public identity and containment calls must allocate nothing\n' >&2
  exit 1
fi

verbose="$(
  javap -classpath "$ELEMENT_CP" -verbose \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext \
    io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement
)"
if grep -Eq \
  'java/util/(HashSet|LinkedHashSet|ArrayList|LinkedList|ArrayDeque|HashMap|LinkedHashMap|TreeMap|Map|List|Iterator)|java/util/concurrent|java/lang/ref/|java/lang/Runnable|CompletableFuture|kotlinx/coroutines|ExecutorService|ScheduledExecutorService|java/lang/reflect|android/view/|android/graphics/|android/os/Handler|java/net/' \
  <<<"$verbose"; then
  printf 'Native element containment must remain owner-confined primitive traversal\n' >&2
  exit 1
fi
