# Web timing rules

- `Performance.now()` and animation-frame timestamps use one supplied monotonic nanosecond time base and one per-object origin. Never use wall-clock time, `java.time`, or `System.currentTimeMillis` as a substitute.
- One `AnimationFrameScheduler` is its reusable host frame callback and its runtime-owned lifecycle resource. Do not allocate a Runnable, RuntimeTask, resolver, or host adapter for each request.
- Frame registrations use reusable parallel-array slots with generation-safe exact-number handles. Do not replace them with a Map, List, boxed handle, or registration node.
- All callbacks captured at the start of one frame run in one runtime host turn and share one timestamp. The microtask checkpoint occurs after the complete callback batch, not between callbacks.
- A callback requested during a frame belongs to a later frame. Cancellation during a frame can suppress a later callback from the current snapshot without making its slot reusable until iteration reaches it.
- Register the animation scheduler with `RuntimeInstance` only while a host frame or callback batch is active. Runtime shutdown must cancel the exact pending host frame and release callback references.
- One `IdleCallbackScheduler` is its reusable idle-host callback, logical timeout task, and runtime-owned lifecycle resource. Do not allocate a host callback, timer task, or registration wrapper per request.
- Idle registrations use reusable parallel-array slots, intrusive pending/runnable lists, and one timeout min-heap. Equal timeout deadlines are ordered by a primitive registration sequence.
- One idle-host notification invokes at most one callback in one host turn. Newly requested callbacks remain pending until a later idle period, and an existing runnable callback stays ahead of a callback reposted by an earlier turn.
- Every timed-out idle callback is a separate host turn with its own microtask checkpoint. Use the scheduler itself for the one earliest logical timer and any due-timeout continuation; never create one RuntimeTask per registration.
- `IdleDeadline.timeRemaining()` uses the supplied monotonic clock, clamps at zero, and ordinary idle delivery is capped to 50ms. A timed-out callback receives zero remaining time and `didTimeout = true`.
- Register the idle scheduler with `RuntimeInstance` only while callbacks, an idle request, a timeout alarm, or a due continuation remain active. Runtime shutdown must cancel the exact host idle request and one logical timeout.
- Timing callback exceptions are reported and later callbacks continue. Fatal JVM errors may escape after scheduler state is restored.
- Do not add generic queues, maps, atomics, futures, coroutines, executors, Android Handler scheduling, polling, reflection, regex, or per-registration allocation on the selected paths.

Run `./scripts/test-timing.sh` before every change.
