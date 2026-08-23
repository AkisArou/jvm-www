#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

mapfile -d '' SOURCES < <(
  find \
    "$ROOT/runtime-core/src/main/java" \
    "$ROOT/runtime-testkit/src/main/java" \
    -name '*.java' -print0 | sort -z
)

javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-options -Werror -d "$OUT" "${SOURCES[@]}"
java -cp "$OUT" io.github.akisarou.jvmwww.testkit.RuntimeInstanceConformance
java -cp "$OUT" io.github.akisarou.jvmwww.testkit.PromiseConformance
java -cp "$OUT" io.github.akisarou.jvmwww.testkit.AsyncFrameConformance

promise_job_shape="$(
  javap -classpath "$OUT" -p \
    'io.github.akisarou.jvmwww.runtime.JsPromise$PromiseJob'
)"
if [[ "$promise_job_shape" != *"extends io.github.akisarou.jvmwww.runtime.RuntimeTask"* ]] || \
   [[ "$promise_job_shape" == *"java.lang.Runnable"* ]]; then
  printf 'Promise jobs must be RuntimeTask values, never one Runnable per reaction\n' >&2
  exit 1
fi

async_frame_shape="$(
  javap -classpath "$OUT" -p io.github.akisarou.jvmwww.runtime.AsyncFrame
)"
if [[ "$async_frame_shape" != *"extends io.github.akisarou.jvmwww.runtime.JsPromise"* ]] || \
   [[ "$async_frame_shape" != *"implements io.github.akisarou.jvmwww.runtime.JsPromise\$PromiseJob"* ]] || \
   [[ "$async_frame_shape" == *"java.lang.Runnable"* ]]; then
  printf 'AsyncFrame must be the result Promise and intrusive runtime job, never a Runnable\n' >&2
  exit 1
fi

if javap -classpath "$OUT" -verbose \
     io.github.akisarou.jvmwww.runtime.JsPromise \
     io.github.akisarou.jvmwww.runtime.AsyncFrame | \
   grep -Eq 'CompletableFuture|kotlinx/coroutines'; then
  printf 'Forbidden future/coroutine dependency in Promise or async-frame core\n' >&2
  exit 1
fi

if command -v node >/dev/null 2>&1; then
  reference_trace="$(node "$ROOT/conformance/reference/promise-ordering.mjs")"
  expected_trace="sync,end,microtask,promise,nested"
  if [[ "$reference_trace" != "$expected_trace" ]]; then
    printf 'Node Promise ordering drift: expected %s, got %s\n' \
      "$expected_trace" "$reference_trace" >&2
    exit 1
  fi
fi
