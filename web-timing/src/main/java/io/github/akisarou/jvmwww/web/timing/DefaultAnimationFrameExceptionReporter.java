package io.github.akisarou.jvmwww.web.timing;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;

/** Default terminal host reporter that preserves later callbacks in the same frame. */
final class DefaultAnimationFrameExceptionReporter implements AnimationFrameExceptionReporter {
    static final DefaultAnimationFrameExceptionReporter INSTANCE =
            new DefaultAnimationFrameExceptionReporter();

    private DefaultAnimationFrameExceptionReporter() {}

    @Override
    public void report(RuntimeInstance runtime, Throwable error) {
        Thread thread = Thread.currentThread();
        Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
        if (handler == null) {
            handler = Thread.getDefaultUncaughtExceptionHandler();
        }
        if (handler == null) {
            return;
        }
        try {
            handler.uncaughtException(thread, error);
        } catch (ThreadDeath fatal) {
            throw fatal;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (LinkageError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // This is the final reporting boundary. A broken host hook must not corrupt the frame
            // list or prevent later callbacks from running.
        }
    }
}
