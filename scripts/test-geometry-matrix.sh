#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/geometry"

mapfile -d '' GEOMETRY_SOURCES < <(
  find "$ROOT/web-geometry/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -d "$OUT/geometry" \
  "${GEOMETRY_SOURCES[@]}" \
  "$ROOT/web-geometry-testkit/src/main/java/io/github/akisarou/jvmwww/web/geometry/testkit/MatrixConformance.java"
GEOMETRY_CP="$OUT/geometry"
java -cp "$GEOMETRY_CP" \
  io.github.akisarou.jvmwww.web.geometry.testkit.MatrixConformance

matrix_shape="$(
  javap -classpath "$GEOMETRY_CP" -p \
    io.github.akisarou.jvmwww.web.geometry.DOMMatrixReadOnly \
    io.github.akisarou.jvmwww.web.geometry.DOMMatrix
)"
for required in \
  'double m11;' 'double m12;' 'double m13;' 'double m14;' \
  'double m21;' 'double m22;' 'double m23;' 'double m24;' \
  'double m31;' 'double m32;' 'double m33;' 'double m34;' \
  'double m41;' 'double m42;' 'double m43;' 'double m44;' \
  'boolean is2D;' \
  'boolean isIdentity()' \
  'DOMMatrix multiplySelf' \
  'DOMMatrix preMultiplySelf' \
  'DOMMatrix invertSelf'; do
  if [[ "$matrix_shape" != *"$required"* ]]; then
    printf 'DOMMatrix primitive boundary is missing: %s\n' "$required" >&2
    exit 1
  fi
done

if compgen -G \
     "$OUT/geometry/io/github/akisarou/jvmwww/web/geometry/DOMMatrix\$*.class" \
     >/dev/null || \
   compgen -G \
     "$OUT/geometry/io/github/akisarou/jvmwww/web/geometry/DOMMatrixReadOnly\$*.class" \
     >/dev/null; then
  printf 'DOMMatrix must not allocate inner adapters or operation wrappers\n' >&2
  exit 1
fi

matrix_code="$(
  javap -classpath "$GEOMETRY_CP" -p -c \
    io.github.akisarou.jvmwww.web.geometry.DOMMatrix
)"
mutating_code="$(
  sed -n \
    '/public io.github.akisarou.jvmwww.web.geometry.DOMMatrix multiplySelf/,/private void clear2DUnlessOne/p' \
    <<<"$matrix_code"
)"
if grep -Eq \
  'new[[:space:]]+#|newarray|anewarray|java/util/|java/lang/Double\.valueOf' \
  <<<"$mutating_code"; then
  printf 'DOMMatrix mutating transforms must remain allocation-free primitive kernels\n' >&2
  exit 1
fi

readonly_code="$(
  javap -classpath "$GEOMETRY_CP" -p -c \
    io.github.akisarou.jvmwww.web.geometry.DOMMatrixReadOnly
)"
transform_section="$(
  sed -n \
    '/public io.github.akisarou.jvmwww.web.geometry.DOMPoint transformPoint/,/final void copyFrom/p' \
    <<<"$readonly_code"
)"
point_allocations="$(
  grep -c 'class io/github/akisarou/jvmwww/web/geometry/DOMPoint' \
    <<<"$transform_section" || true
)"
if [[ "$point_allocations" -ne 1 ]] || \
   grep -Eq 'newarray|anewarray|java/util/' <<<"$transform_section"; then
  printf 'DOMMatrix.transformPoint must allocate only its specification-visible DOMPoint result\n' >&2
  exit 1
fi

point_code="$(
  javap -classpath "$GEOMETRY_CP" -p -c \
    io.github.akisarou.jvmwww.web.geometry.DOMPointReadOnly
)"
if [[ "$point_code" != *'DOMMatrixReadOnly.transformPoint'* ]]; then
  printf 'DOMPoint.matrixTransform must delegate to the shared primitive matrix transform\n' >&2
  exit 1
fi

geometry_verbose="$(
  javap -classpath "$GEOMETRY_CP" -verbose \
    io.github.akisarou.jvmwww.web.geometry.DOMPointReadOnly \
    io.github.akisarou.jvmwww.web.geometry.DOMMatrixReadOnly \
    io.github.akisarou.jvmwww.web.geometry.DOMMatrix
)"
for required in \
  'java/lang/Math.sin' \
  'java/lang/Math.cos' \
  'java/lang/Math.tan' \
  'java/lang/Math.atan2' \
  'java/lang/Math.hypot'; do
  if [[ "$geometry_verbose" != *"$required"* ]]; then
    printf 'DOMMatrix primitive transform math is missing: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq \
  'io/github/akisarou/jvmwww/runtime|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|LinkedHashMap|TreeMap|Map|List)|java/util/concurrent|java/lang/ref/|java/lang/Runnable|CompletableFuture|kotlinx/coroutines|ExecutorService|ScheduledExecutorService|android/|java/awt/|java/io/Serializable|java/lang/reflect|java/net/' \
  <<<"$geometry_verbose"; then
  printf 'DOMMatrix must remain primitive synchronous geometry with no runtime, collection, scheduler, platform, or Java-serialization dependency\n' >&2
  exit 1
fi
