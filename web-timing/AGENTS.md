# Web timing rules

- `Performance.now()` and animation-frame timestamps use one supplied monotonic nanosecond time base and one per-object origin. Never use wall-clock time, `java.time`, or `System.currentTimeMillis` as a substitute.
- One `AnimationFrameScheduler` is its reusable host frame callback and its runtime-owned lifecycle resource. Do not allocate a Runnable, RuntimeTask, resolver, or host adapter for each request.
- Frame registrations use reusable parallel-array slots with generation-safe exact-number handles. Do not replace them with a Map, List, boxed handle, or registration node.
- All callbacks captured at the start of one frame run in one runtime host turn and share one timestamp. The microtask checkpoint occurs after the complete callback batch, not between callbacks.
- A callback requested during a frame belongs to a later frame. Cancellation during a frame can suppress a later callback from the current snapshot without making its slot reusable until iteration reaches it.
- Register the scheduler with `RuntimeInstance` only while a host frame or callback batch is active. Runtime shutdown must cancel the exact pending host frame and release callback references.
- Callback exceptions are reported and later callbacks continue. Fatal JVM errors may escape after the scheduler restores its internal lists.
- Do not add generic queues, atomics, futures, coroutines, executors, Android Handler scheduling, polling, reflection, regex, or per-frame allocation on the selected path.

Run `./scripts/test-timing.sh` before every change.
