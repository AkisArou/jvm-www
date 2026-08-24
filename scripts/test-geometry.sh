#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/geometry"

mapfile -d '' GEOMETRY_SOURCES < <(
  find "$ROOT/web-geometry/src/main/java" \
       "$ROOT/web-geometry-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -d "$OUT/geometry" "${GEOMETRY_SOURCES[@]}"
GEOMETRY_CP="$OUT/geometry"
java -cp "$GEOMETRY_CP" \
  io.github.akisarou.jvmwww.web.geometry.testkit.GeometryConformance

point_shape="$(
  javap -classpath "$GEOMETRY_CP" -p \
    io.github.akisarou.jvmwww.web.geometry.DOMPointReadOnly \
    io.github.akisarou.jvmwww.web.geometry.DOMPoint
)"
for required in \
  'double x;' \
  'double y;' \
  'double z;' \
  'double w;' \
  'double getX()' \
  'double getY()' \
  'double getZ()' \
  'double getW()' \
  'void setX(double)' \
  'void setY(double)' \
  'void setZ(double)' \
  'void setW(double)'; do
  if [[ "$point_shape" != *"$required"* ]]; then
    printf 'DOMPoint primitive/read-write boundary is missing: %s\n' "$required" >&2
    exit 1
  fi
done

rect_shape="$(
  javap -classpath "$GEOMETRY_CP" -p \
    io.github.akisarou.jvmwww.web.geometry.DOMRectReadOnly \
    io.github.akisarou.jvmwww.web.geometry.DOMRect
)"
for required in \
  'double x;' \
  'double y;' \
  'double width;' \
  'double height;' \
  'double getTop()' \
  'double getRight()' \
  'double getBottom()' \
  'double getLeft()' \
  'void setWidth(double)' \
  'void setHeight(double)'; do
  if [[ "$rect_shape" != *"$required"* ]]; then
    printf 'DOMRect primitive/derived-edge boundary is missing: %s\n' "$required" >&2
    exit 1
  fi
done

quad_shape="$(
  javap -classpath "$GEOMETRY_CP" -p \
    io.github.akisarou.jvmwww.web.geometry.DOMQuad
)"
for required in \
  'private final io.github.akisarou.jvmwww.web.geometry.DOMPoint p1;' \
  'private final io.github.akisarou.jvmwww.web.geometry.DOMPoint p2;' \
  'private final io.github.akisarou.jvmwww.web.geometry.DOMPoint p3;' \
  'private final io.github.akisarou.jvmwww.web.geometry.DOMPoint p4;' \
  'io.github.akisarou.jvmwww.web.geometry.DOMRect getBounds()'; do
  if [[ "$quad_shape" != *"$required"* ]]; then
    printf 'DOMQuad same-object primitive boundary is missing: %s\n' "$required" >&2
    exit 1
  fi
done

if compgen -G \
     "$OUT/geometry/io/github/akisarou/jvmwww/web/geometry/DOMPointReadOnly\$*.class" \
     >/dev/null || \
   compgen -G \
     "$OUT/geometry/io/github/akisarou/jvmwww/web/geometry/DOMRectReadOnly\$*.class" \
     >/dev/null || \
   compgen -G \
     "$OUT/geometry/io/github/akisarou/jvmwww/web/geometry/DOMQuad\$*.class" \
     >/dev/null; then
  printf 'Geometry values must not allocate inner adapters or dictionary wrappers\n' >&2
  exit 1
fi

quad_code="$(
  javap -classpath "$GEOMETRY_CP" -p -c \
    io.github.akisarou.jvmwww.web.geometry.DOMQuad
)"
bounds_section="$(
  sed -n '/public io.github.akisarou.jvmwww.web.geometry.DOMRect getBounds()/,/private static/p' \
    <<<"$quad_code"
)"
if [[ "$bounds_section" != *'GeometryMath.minimum'* ]] || \
   [[ "$bounds_section" != *'GeometryMath.maximum'* ]] || \
   [[ "$bounds_section" != *'DOMRect."<init>"'* ]]; then
  printf 'DOMQuad.getBounds must calculate directly into one result rectangle\n' >&2
  exit 1
fi
if grep -Eq 'anewarray|newarray|java/util/|java/lang/Double\.valueOf' <<<"$bounds_section"; then
  printf 'DOMQuad.getBounds must not allocate coordinate arrays, collections, or boxed doubles\n' >&2
  exit 1
fi

from_rect_section="$(
  sed -n '/public static io.github.akisarou.jvmwww.web.geometry.DOMQuad fromRect(double, double, double, double)/,/public static io.github.akisarou.jvmwww.web.geometry.DOMQuad fromQuad()/p' \
    <<<"$quad_code"
)"
point_constructor_count="$(grep -c 'DOMPoint."<init>"' <<<"$from_rect_section" || true)"
if [[ "$point_constructor_count" -ne 4 ]]; then
  printf 'DOMQuad.fromRect must allocate exactly four owned points, got %s constructor calls\n' \
    "$point_constructor_count" >&2
  exit 1
fi
if [[ "$from_rect_section" == *'copyPoint'* ]]; then
  printf 'DOMQuad.fromRect must not copy the four points it just created\n' >&2
  exit 1
fi

geometry_verbose="$(
  javap -classpath "$GEOMETRY_CP" -verbose \
    io.github.akisarou.jvmwww.web.geometry.DOMPointReadOnly \
    io.github.akisarou.jvmwww.web.geometry.DOMPoint \
    io.github.akisarou.jvmwww.web.geometry.DOMRectReadOnly \
    io.github.akisarou.jvmwww.web.geometry.DOMRect \
    io.github.akisarou.jvmwww.web.geometry.DOMQuad \
    io.github.akisarou.jvmwww.web.geometry.GeometryMath
)"
for required in \
  'java/lang/Math.min' \
  'java/lang/Math.max'; do
  if [[ "$geometry_verbose" != *"$required"* ]]; then
    printf 'Geometry extrema are missing IEEE-754 primitive: %s\n' "$required" >&2
    exit 1
  fi
done
if grep -Eq \
  'io/github/akisarou/jvmwww/runtime|java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|LinkedHashMap|TreeMap|Map|List)|java/util/concurrent|java/lang/ref/|java/lang/Runnable|CompletableFuture|kotlinx/coroutines|ExecutorService|ScheduledExecutorService|android/|java/io/Serializable|java/lang/reflect|java/net/' \
  <<<"$geometry_verbose"; then
  printf 'web-geometry must remain primitive value math with no runtime, collection, scheduler, platform, or Java-serialization dependency\n' >&2
  exit 1
fi
