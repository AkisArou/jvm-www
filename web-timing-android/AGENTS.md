# Android timing adapter rules

- `ChoreographerAnimationFrameHost` is both the `AnimationFrameHost` and the one reusable `Choreographer.FrameCallback`. Never allocate a platform callback, `Runnable`, or registration wrapper per frame request.
- Construct it on the runtime owner Looper with `forCurrentLooper()`. Every clock, request, cancellation, and delivery operation is confined by exact Looper identity.
- `nowNanos()` uses `System.nanoTime()` directly because Choreographer frame timestamps occupy that monotonic nanosecond domain. Do not convert through wall time, `SystemClock.uptimeMillis`, or a separately sampled delay.
- Retain at most one exact scheduler callback. A failed post rolls the field back; a failed removal leaves the callback retained so cancellation can be retried and delivery remains safe.
- Clear the pending callback before forwarding `doFrame`, allowing a callback requested during the frame batch to post the following display frame.
- Keep Web callback ordering, handle generation, runtime ownership, and microtask checkpoints in `web-timing`. This module contains only Android attachment and clock plumbing.
- Do not add `Handler`, executors, futures, coroutines, atomics, generic collections, reflection, API-level branching, or polling.

Run `./scripts/test-timing-android.sh` before every change.
