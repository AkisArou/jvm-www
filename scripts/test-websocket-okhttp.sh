#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p \
  "$OUT/core" \
  "$OUT/events" \
  "$OUT/encoding" \
  "$OUT/url" \
  "$OUT/bodies" \
  "$OUT/websocket" \
  "$OUT/okhttp"

mapfile -d '' CORE_SOURCES < <(
  find "$ROOT/runtime-core/src/main/java" "$ROOT/runtime-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -d "$OUT/core" "${CORE_SOURCES[@]}"

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

mapfile -d '' WEBSOCKET_SOURCES < <(
  find "$ROOT/websocket-core/src/main/java" -name '*.java' -print0 | sort -z
)
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/bodies" \
  -d "$OUT/websocket" "${WEBSOCKET_SOURCES[@]}"

mapfile -d '' OKHTTP_SOURCES < <(
  find "$ROOT/websocket-okhttp/src/main/java" \
       "$ROOT/websocket-okhttp-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)
OKHTTP_CP="$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/bodies:$OUT/websocket:$OUT/okhttp"
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror \
  -cp "$OUT/core:$OUT/events:$OUT/encoding:$OUT/url:$OUT/bodies:$OUT/websocket" \
  -d "$OUT/okhttp" "${OKHTTP_SOURCES[@]}"
java -cp "$OKHTTP_CP" \
  io.github.akisarou.jvmwww.web.websocket.okhttp.testkit.OkHttpWebSocketTransportConformance

bridge_shape="$(
  javap -classpath "$OKHTTP_CP" -p \
    'io.github.akisarou.jvmwww.web.websocket.okhttp.OkHttpWebSocketTransport$OkHttpWebSocketCall'
)"
if [[ "$bridge_shape" != *"extends okhttp3.WebSocketListener"* ]] || \
   [[ "$bridge_shape" != *"io.github.akisarou.jvmwww.web.websocket.WebSocketTransportCall"* ]] || \
   [[ "$bridge_shape" == *"java.lang.Runnable"* ]]; then
  printf 'OkHttp WebSocket bridge must fuse WebSocketListener and WebSocketTransportCall without Runnable\n' >&2
  exit 1
fi

bridge_count="$(
  find "$OUT/okhttp/io/github/akisarou/jvmwww/web/websocket/okhttp" \
    -maxdepth 1 -name 'OkHttpWebSocketTransport$*.class' -print | wc -l
)"
if [[ "$bridge_count" -ne 1 ]]; then
  printf 'OkHttp WebSocket transport must own exactly one callback/call bridge, got %s\n' \
    "$bridge_count" >&2
  exit 1
fi

snapshot_shape="$(
  javap -classpath "$OKHTTP_CP" -p \
    io.github.akisarou.jvmwww.web.bodies.BufferedBodySnapshot
)"
if [[ "$snapshot_shape" != *"java.nio.ByteBuffer asReadOnlyByteBuffer()"* ]]; then
  printf 'BufferedBodySnapshot must expose the read-only no-copy transport view\n' >&2
  exit 1
fi

transport_verbose="$(
  javap -classpath "$OKHTTP_CP" -verbose \
    io.github.akisarou.jvmwww.web.websocket.okhttp.OkHttpWebSocketTransport \
    'io.github.akisarou.jvmwww.web.websocket.okhttp.OkHttpWebSocketTransport$OkHttpWebSocketCall'
)"
for required in \
  'okhttp3/WebSocket$Factory.newWebSocket' \
  'io/github/akisarou/jvmwww/web/bodies/BufferedBodySnapshot.asReadOnlyByteBuffer' \
  'okio/ByteString.of:(Ljava/nio/ByteBuffer;)Lokio/ByteString;' \
  'okio/ByteString.toByteArray:()[B' \
  'okhttp3/WebSocket.send:(Ljava/lang/String;)Z' \
  'okhttp3/WebSocket.send:(Lokio/ByteString;)Z' \
  'okhttp3/WebSocket.queueSize:()J' \
  'okhttp3/WebSocket.close:(ILjava/lang/String;)Z' \
  'okhttp3/WebSocket.cancel:()V'; do
  if [[ "$transport_verbose" != *"$required"* ]]; then
    printf 'websocket-okhttp is missing required direct transport primitive: %s\n' \
      "$required" >&2
    exit 1
  fi
done

if [[ "$transport_verbose" == *"BufferedBodySnapshot.copyBytes"* ]]; then
  printf 'websocket-okhttp must not copy the snapshot before ByteString performs its one required copy\n' >&2
  exit 1
fi

if grep -Eq \
    'CompletableFuture|kotlinx/coroutines|ScheduledExecutorService|ExecutorService|android/os/Handler|java/net/(URI|URL)|java/io/(ByteArrayInputStream|ByteArrayOutputStream)|java/util/Base64|java/util/regex|java/util/concurrent/atomic|java/util/(ArrayList|LinkedList|ArrayDeque|ConcurrentLinkedQueue|HashMap|TreeMap)|java/lang/Runnable' \
    <<<"$transport_verbose"; then
  printf 'websocket-okhttp must remain direct transport plumbing without queues, copies, schedulers, URL semantics, base64, or Runnable paths\n' >&2
  exit 1
fi
