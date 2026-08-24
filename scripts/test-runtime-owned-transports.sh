#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/events" "$OUT/encoding" "$OUT/url" "$OUT/bodies" "$OUT/fetch" "$OUT/websocket"

mapfile -d '' CORE_SOURCES < <(
  find "$ROOT/runtime-core/src/main/java" "$ROOT/runtime-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -d "$OUT/core" "${CORE_SOURCES[@]}"
java -cp "$OUT/core" \
  io.github.akisarou.jvmwww.testkit.RuntimeOwnedResourceConformance

mapfile -d '' EVENT_SOURCES < <(
  find "$ROOT/web-events/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core" -d "$OUT/events" "${EVENT_SOURCES[@]}"

mapfile -d '' ENCODING_SOURCES < <(
  find "$ROOT/web-encoding/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core" -d "$OUT/encoding" "${ENCODING_SOURCES[@]}"

mapfile -d '' URL_SOURCES < <(
  find "$ROOT/web-url/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/encoding" -d "$OUT/url" "${URL_SOURCES[@]}"

mapfile -d '' BODY_SOURCES < <(
  find "$ROOT/web-bodies/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/encoding:$OUT/url" -d "$OUT/bodies" "${BODY_SOURCES[@]}"

mapfile -d '' FETCH_SOURCES < <(
  find "$ROOT/web-fetch-core/src/main/java" "$ROOT/web-fetch-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
FETCH_CP="$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/bodies:$OUT/fetch"
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/bodies" \
  -d "$OUT/fetch" "${FETCH_SOURCES[@]}"
java -cp "$FETCH_CP" \
  io.github.akisarou.jvmwww.web.fetch.testkit.FetchRuntimeOwnershipConformance

mapfile -d '' WEBSOCKET_SOURCES < <(
  find "$ROOT/websocket-core/src/main/java" "$ROOT/websocket-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
WEBSOCKET_CP="$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/bodies:$OUT/websocket"
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/bodies" \
  -d "$OUT/websocket" "${WEBSOCKET_SOURCES[@]}"
java -cp "$WEBSOCKET_CP" \
  io.github.akisarou.jvmwww.web.websocket.testkit.WebSocketRuntimeOwnershipConformance

registry_shape="$(javap -classpath "$OUT/core" -p \
  io.github.akisarou.jvmwww.runtime.RuntimeInstance \
  io.github.akisarou.jvmwww.runtime.RuntimeResourceRegistry)"
for required in \
  'volatile io.github.akisarou.jvmwww.runtime.RuntimeResourceRegistry resourceRegistry' \
  'int registerOwnedResource(io.github.akisarou.jvmwww.runtime.RuntimeOwnedResource)' \
  'boolean unregisterOwnedResource(io.github.akisarou.jvmwww.runtime.RuntimeOwnedResource, int)' \
  'io.github.akisarou.jvmwww.runtime.RuntimeOwnedResource[] resources' \
  'int[] nextFreeSlot' \
  'io.github.akisarou.jvmwww.runtime.RuntimeOwnedResource[] detach()'; do
  if [[ "$registry_shape" != *"$required"* ]]; then
    printf 'runtime-owned resource registry is missing compact slot primitive: %s\n' "$required" >&2
    exit 1
  fi
done

registry_verbose="$(javap -classpath "$OUT/core" -verbose \
  io.github.akisarou.jvmwww.runtime.RuntimeInstance \
  io.github.akisarou.jvmwww.runtime.RuntimeResourceRegistry)"
if [[ "$registry_verbose" != *'java/lang/System.arraycopy'* ]] || \
   grep -Eq \
     'java/util/(ArrayList|LinkedList|ArrayDeque|HashMap|IdentityHashMap|ConcurrentHashMap)|java/util/concurrent/atomic|java/lang/ref|java/lang/Runnable' \
     <<<"$(javap -classpath "$OUT/core" -verbose io.github.akisarou.jvmwww.runtime.RuntimeResourceRegistry)"; then
  printf 'runtime-owned resources must use reusable primitive slots without maps, nodes, atomics, weak references, or Runnables\n' >&2
  exit 1
fi

fetch_shape="$(javap -classpath "$FETCH_CP" -p io.github.akisarou.jvmwww.web.fetch.FetchOperation)"
for required in \
  'io.github.akisarou.jvmwww.runtime.RuntimeOwnedResource' \
  'int runtimeResourceSlot' \
  'void closeForRuntime()'; do
  if [[ "$fetch_shape" != *"$required"* ]]; then
    printf 'FetchOperation is missing fused runtime ownership: %s\n' "$required" >&2
    exit 1
  fi
done

socket_shape="$(javap -classpath "$WEBSOCKET_CP" -p io.github.akisarou.jvmwww.web.websocket.WebSocket)"
for required in \
  'io.github.akisarou.jvmwww.runtime.RuntimeOwnedResource' \
  'int runtimeResourceSlot' \
  'void closeForRuntime()'; do
  if [[ "$socket_shape" != *"$required"* ]]; then
    printf 'WebSocket is missing fused runtime ownership: %s\n' "$required" >&2
    exit 1
  fi
done

ownership_verbose="$(javap -classpath "$FETCH_CP:$OUT/websocket" -verbose \
  io.github.akisarou.jvmwww.web.fetch.FetchOperation \
  io.github.akisarou.jvmwww.web.websocket.WebSocket)"
for required in \
  'io/github/akisarou/jvmwww/runtime/RuntimeInstance.registerOwnedResource' \
  'io/github/akisarou/jvmwww/runtime/RuntimeInstance.unregisterOwnedResource'; do
  if [[ "$ownership_verbose" != *"$required"* ]]; then
    printf 'transport object is missing runtime ownership call: %s\n' "$required" >&2
    exit 1
  fi
done

if find "$OUT" -name '*RuntimeResourceRegistration*.class' -print -quit | grep -q .; then
  printf 'runtime ownership must not allocate one registration object per transport\n' >&2
  exit 1
fi
