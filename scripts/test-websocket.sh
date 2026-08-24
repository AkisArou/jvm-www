#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/core" "$OUT/events" "$OUT/encoding" "$OUT/url" "$OUT/bodies" "$OUT/websocket"

mapfile -d '' CORE_SOURCES < <(
  find "$ROOT/runtime-core/src/main/java" "$ROOT/runtime-testkit/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror -d "$OUT/core" "${CORE_SOURCES[@]}"

mapfile -d '' EVENT_SOURCES < <(find "$ROOT/web-events/src/main/java" -name '*.java' -print0 | sort -z)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror -cp "$OUT/core" -d "$OUT/events" "${EVENT_SOURCES[@]}"

mapfile -d '' ENCODING_SOURCES < <(find "$ROOT/web-encoding/src/main/java" -name '*.java' -print0 | sort -z)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror -cp "$OUT/core" -d "$OUT/encoding" "${ENCODING_SOURCES[@]}"

mapfile -d '' URL_SOURCES < <(find "$ROOT/web-url/src/main/java" -name '*.java' -print0 | sort -z)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror -cp "$OUT/core:$OUT/encoding" -d "$OUT/url" "${URL_SOURCES[@]}"

mapfile -d '' BODY_SOURCES < <(find "$ROOT/web-bodies/src/main/java" -name '*.java' -print0 | sort -z)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror -cp "$OUT/core:$OUT/encoding:$OUT/url" -d "$OUT/bodies" "${BODY_SOURCES[@]}"

mapfile -d '' WS_SOURCES < <(
  find "$ROOT/websocket-core/src/main/java" "$ROOT/websocket-testkit/src/main/java" -name '*.java' -print0 | sort -z
)
WS_CP="$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/bodies:$OUT/websocket"
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/bodies" -d "$OUT/websocket" "${WS_SOURCES[@]}"
java -cp "$WS_CP" io.github.akisarou.jvmwww.web.websocket.testkit.WebSocketConformance

socket_shape="$(javap -classpath "$WS_CP" -p io.github.akisarou.jvmwww.web.websocket.WebSocket)"
for required in \
  'extends io.github.akisarou.jvmwww.web.events.EventTarget' \
  'io.github.akisarou.jvmwww.web.websocket.WebSocketTransportListener' \
  'io.github.akisarou.jvmwww.web.events.EventListener' \
  'io.github.akisarou.jvmwww.runtime.RuntimeTask'; do
  if [[ "$socket_shape" != *"$required"* ]]; then
    printf 'WebSocket is missing fused owner/transport/event boundary: %s\n' "$required" >&2
    exit 1
  fi
done
if [[ "$socket_shape" == *'java.lang.Runnable'* ]]; then
  printf 'WebSocket must never be a platform Runnable\n' >&2
  exit 1
fi

queued_shape="$(javap -classpath "$WS_CP" -p \
  io.github.akisarou.jvmwww.web.websocket.QueuedWebSocketEvent \
  io.github.akisarou.jvmwww.web.websocket.MessageEvent \
  io.github.akisarou.jvmwww.web.websocket.CloseEvent)"
if [[ "$queued_shape" != *'QueuedWebSocketEvent nextWebSocketEvent'* ]] || \
   [[ "$queued_shape" != *'MessageEvent extends io.github.akisarou.jvmwww.web.websocket.QueuedWebSocketEvent'* ]] || \
   [[ "$queued_shape" != *'CloseEvent extends io.github.akisarou.jvmwww.web.websocket.QueuedWebSocketEvent'* ]]; then
  printf 'WebSocket events must be their own intrusive queue nodes\n' >&2
  exit 1
fi

call_shape="$(javap -classpath "$WS_CP" -p io.github.akisarou.jvmwww.web.websocket.WebSocketTransportCall)"
for required in 'long getQueuedByteCount()' 'boolean close(int, java.lang.String)'; do
  if [[ "$call_shape" != *"$required"* ]]; then
    printf 'WebSocket transport is missing allocation-free queue/close primitive: %s\n' "$required" >&2
    exit 1
  fi
done

ws_verbose="$(javap -classpath "$WS_CP" -verbose \
  io.github.akisarou.jvmwww.web.websocket.WebSocket \
  io.github.akisarou.jvmwww.web.websocket.MessageEvent \
  io.github.akisarou.jvmwww.web.websocket.CloseEvent \
  io.github.akisarou.jvmwww.web.websocket.WebSocketTransportRequest)"
for required in \
  'io/github/akisarou/jvmwww/runtime/RuntimeInstance.admitHostTask' \
  'io/github/akisarou/jvmwww/web/encoding/Utf8Codec.encodedLength' \
  'io/github/akisarou/jvmwww/web/bodies/BufferedBodySnapshot.fromOwnedBytes' \
  'io/github/akisarou/jvmwww/web/bodies/Blob.fromSnapshot' \
  'io/github/akisarou/jvmwww/web/bodies/Blob.snapshot' \
  'io/github/akisarou/jvmwww/web/websocket/WebSocketTransportCall.getQueuedByteCount' \
  'java/lang/System.arraycopy'; do
  if [[ "$ws_verbose" != *"$required"* ]]; then
    printf 'websocket-core is missing required ownership/performance primitive: %s\n' "$required" >&2
    exit 1
  fi
done

if grep -Eq \
  'java/util/(ArrayList|LinkedList|ArrayDeque|ConcurrentLinkedQueue|HashMap|TreeMap)|java/util/concurrent/atomic|java/io/(ByteArrayInputStream|ByteArrayOutputStream)|java/nio/(ByteBuffer|charset)|java/util/Base64|java/util/regex|CompletableFuture|kotlinx/coroutines|ScheduledExecutorService|ExecutorService|android/os/Handler|java/net/(URI|URL)|java/lang/Runnable' \
  <<<"$ws_verbose"; then
  printf 'websocket-core must avoid generic queues, copies, schedulers, java.net, base64, charset, and Runnable paths\n' >&2
  exit 1
fi
