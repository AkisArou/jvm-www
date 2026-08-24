# Android timing adapter rules

- `ChoreographerAnimationFrameHost` is both the `AnimationFrameHost` and the one reusable `Choreographer.FrameCallback`. Never allocate a platform callback, `Runnable`, or registration wrapper per frame request.
- Construct it on the runtime owner Looper with `forCurrentLooper()`. Every clock, request, cancellation, and delivery operation is confined by exact Looper identity.
- `nowNanos()` uses `System.nanoTime()` directly because Choreographer frame timestamps occupy that monotonic nanosecond domain. Do not convert through wall time, `SystemClock.uptimeMillis`, or a separately sampled delay.
- Retain at most one exact scheduler callback. A failed post rolls the field back; a failed removal leaves the callback retained so cancellation can be retried and delivery remains safe.
- Clear the pending callback before forwarding `doFrame`, allowing a callback requested during the frame batch to post the following display frame.
- `MessageQueueIdleCallbackHost` is both the `IdleCallbackHost` and the one reusable `MessageQueue.IdleHandler`. It retains one scheduler callback and never allocates a handler, token, `Runnable`, or registration wrapper per idle request.
- Obtain the queue through `Looper.myQueue()` on the runtime owner and use `System.nanoTime()` for both `nowNanos()` and the conservative deadline. The default budget is one millisecond; the platform-independent core still caps an ordinary idle deadline at 50 milliseconds.
- Clear the delivered callback before forwarding `queueIdle`. A callback rearmed during delivery keeps the same Android registration by making `queueIdle()` return true; never call `addIdleHandler` again from inside that delivery.
- Exact cancellation outside delivery calls `removeIdleHandler(this)` and clears state only after removal succeeds. Cancellation during delivery clears the rearmed callback and lets `queueIdle()` return false.
- Returning true does not create a periodic pump. Remaining untimed callbacks wait until Android reaches another queue-idle cycle; timeout delivery remains owned by `IdleCallbackScheduler` and the runtime timer heap.
- Keep Web callback ordering, handle generation, runtime ownership, timeout races, and microtask checkpoints in `web-timing`. This module contains only Android attachment and monotonic clock plumbing.
- Do not add `Handler`, executors, futures, coroutines, atomics, generic collections, reflection, API-level branching, or polling.

Run `./scripts/test-timing-android.sh` before every change.
