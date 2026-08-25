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
  io.github.akisarou.jvmwww.web.nativeelements.testkit.NativeElementRelationConformance
java -cp "$ELEMENT_CP" \
  io.github.akisarou.jvmwww.web.nativeelements.android.testkit.AndroidNativeElementRelationConformance

core_shape="$(
  javap -classpath "$ELEMENT_CP" -p \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext \
    io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement
)"
for required in \
  'NativeElementContext implements io.github.akisarou.jvmwww.web.nativeelements.NativeElementRectSink,io.github.akisarou.jvmwww.web.nativeelements.NativeElementOffsetSink,io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink' \
  'private long measuredRelatedElementIdentity;' \
  'private long activeElementIdentity;' \
  'void setRelatedElement(long)' \
  'boolean readParentElement(long, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink)' \
  'boolean readFirstElementChild(long, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink)' \
  'boolean readLastElementChild(long, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink)' \
  'boolean readPreviousElementSibling(long, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink)' \
  'boolean readNextElementSibling(long, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink)' \
  'int getChildElementCount(long)' \
  'io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement getParentElement()' \
  'io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement getFirstElementChild()' \
  'io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement getLastElementChild()' \
  'io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement getPreviousElementSibling()' \
  'io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement getNextElementSibling()' \
  'int getChildElementCount()'; do
  if [[ "$core_shape" != *"$required"* ]]; then
    printf 'Native element relation boundary is missing: %s\n' "$required" >&2
    exit 1
  fi
done

if compgen -G \
     "$OUT/native-elements/io/github/akisarou/jvmwww/web/nativeelements/NativeElementContext\$*.class" \
     >/dev/null; then
  printf 'NativeElementContext must not generate a relation callback adapter\n' >&2
  exit 1
fi

context_code="$(
  javap -classpath "$ELEMENT_CP" -p -c \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext
)"
relation_section="$(
  sed -n \
    '/private io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement getRelatedElement(long, byte)/,/^}/p' \
    <<<"$context_code"
)"
for required in \
  'NativeElementHost.readParentElement' \
  'NativeElementHost.readFirstElementChild' \
  'NativeElementHost.readLastElementChild' \
  'NativeElementHost.readPreviousElementSibling' \
  'NativeElementHost.readNextElementSibling' \
  'createElement'; do
  if [[ "$relation_section" != *"$required"* ]]; then
    printf 'Related-element lookup is missing direct operation: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq 'new.*class io/github/akisarou/jvmwww/web/nativeelements/ReactNativeElement' \
     <<<"$relation_section" || \
   grep -Eq 'newarray|anewarray|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|Map|List)|java/lang/(Long|Integer)\.valueOf' \
     <<<"$relation_section"; then
  printf 'Related-element lookup may allocate only through the one-wrapper createElement cache miss\n' >&2
  exit 1
fi

count_section="$(
  sed -n \
    '/int getChildElementCount(long)/,/private io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement getRelatedElement(long, byte)/p' \
    <<<"$context_code"
)"
if [[ "$count_section" != *'NativeElementHost.getChildElementCount'* ]]; then
  printf 'childElementCount must call the primitive host scalar directly\n' >&2
  exit 1
fi
if grep -Eq 'newarray|anewarray|java/util/|java/lang/(Long|Integer)\.valueOf' \
    <<<"$count_section"; then
  printf 'childElementCount must remain an allocation-free primitive scalar read\n' >&2
  exit 1
fi

android_shape="$(
  javap -classpath "$ELEMENT_CP" -p \
    io.github.akisarou.jvmwww.web.nativeelements.android.AndroidNativeElementHost
)"
for required in \
  'private static final byte STATE_HAS_ELEMENT_RELATIONS;' \
  'private long[] elementRelations;' \
  'private int[] childElementCounts;' \
  'boolean commitElementRelations(long, long, long, long, long, long, int)' \
  'boolean clearCommittedElementRelations(long)' \
  'boolean readParentElement(long, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink)' \
  'boolean readFirstElementChild(long, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink)' \
  'boolean readLastElementChild(long, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink)' \
  'boolean readPreviousElementSibling(long, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink)' \
  'boolean readNextElementSibling(long, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink)' \
  'int getChildElementCount(long)' \
  'private boolean readElementRelation(long, int, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink)'; do
  if [[ "$android_shape" != *"$required"* ]]; then
    printf 'Android committed relation table is missing: %s\n' "$required" >&2
    exit 1
  fi
done

if compgen -G \
     "$OUT/android-elements/io/github/akisarou/jvmwww/web/nativeelements/android/AndroidNativeElementHost\$*.class" \
     >/dev/null; then
  printf 'Android relation storage must not generate a per-relation wrapper class\n' >&2
  exit 1
fi

android_code="$(
  javap -classpath "$ELEMENT_CP" -p -c \
    io.github.akisarou.jvmwww.web.nativeelements.android.AndroidNativeElementHost
)"
commit_section="$(
  sed -n \
    '/public boolean commitElementRelations(long, long, long, long, long, long, int)/,/public boolean commitClientAndScrollMetrics/p' \
    <<<"$android_code"
)"
for required in 'lastore' 'iastore'; do
  if [[ "$commit_section" != *"$required"* ]]; then
    printf 'Android relation publication is missing primitive store: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq 'new[[:space:]]+#|newarray|anewarray|java/util/|java/lang/(Long|Integer)\.valueOf' \
    <<<"$commit_section"; then
  printf 'Android relation publication must remain allocation-free primitive array writes\n' >&2
  exit 1
fi

read_section="$(
  sed -n \
    '/private boolean readElementRelation(long, int, io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink)/,/private double readClientAndScrollMetric/p' \
    <<<"$android_code"
)"
for required in 'laload' 'NativeElementRelationSink.setRelatedElement'; do
  if [[ "$read_section" != *"$required"* ]]; then
    printf 'Android relation read is missing direct primitive operation: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq 'new[[:space:]]+#|newarray|anewarray|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|Map|List)|java/lang/(Long|Integer)\.valueOf' \
    <<<"$read_section"; then
  printf 'Android relation reads must allocate no tuple, wrapper, map entry, or boxed identity\n' >&2
  exit 1
fi

android_count_section="$(
  sed -n \
    '/public int getChildElementCount(long)/,/public double getClientWidth(long)/p' \
    <<<"$android_code"
)"
for required in 'iaload' 'laload'; do
  if [[ "$android_count_section" != *"$required"* ]]; then
    printf 'Android child count is missing generation-safe primitive validation: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq 'new[[:space:]]+#|newarray|anewarray|java/util/|java/lang/(Long|Integer)\.valueOf' \
    <<<"$android_count_section"; then
  printf 'Android child count must remain an allocation-free primitive read\n' >&2
  exit 1
fi

verbose="$(
  javap -classpath "$ELEMENT_CP" -verbose \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementRelationSink \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementHost \
    io.github.akisarou.jvmwww.web.nativeelements.NativeElementContext \
    io.github.akisarou.jvmwww.web.nativeelements.ReactNativeElement \
    io.github.akisarou.jvmwww.web.nativeelements.android.AndroidNativeElementHost
)"
if [[ "$verbose" != *'java/lang/System.arraycopy'* ]] || \
   [[ "$verbose" != *'NativeElementRelationSink.setRelatedElement'* ]]; then
  printf 'Relation profile is missing direct array growth or sink publication evidence\n' >&2
  exit 1
fi
if grep -Eq \
  'android/view/|android/graphics/|android/os/Handler|android/util/(SparseArray|LongSparseArray)|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|LinkedHashMap|TreeMap|Map|List)|java/util/concurrent|java/lang/ref/|java/lang/Runnable|CompletableFuture|kotlinx/coroutines|ExecutorService|ScheduledExecutorService|java/lang/reflect|java/net/' \
  <<<"$verbose"; then
  printf 'Native element relations must not add Views, maps, schedulers, reflection, or networking\n' >&2
  exit 1
fi
if grep -Eq 'monitorenter|monitorexit' <<<"$android_code"; then
  printf 'Android native element relations are Looper-confined and must not add locking\n' >&2
  exit 1
fi
