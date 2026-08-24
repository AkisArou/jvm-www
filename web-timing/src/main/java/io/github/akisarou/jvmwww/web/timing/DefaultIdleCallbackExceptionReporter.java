package io.github.akisarou.jvmwww.web.timing;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;

/** Default terminal host reporter that leaves later idle callbacks runnable. */
final class DefaultIdleCallbackExceptionReporter implements IdleCallbackExceptionReporter {
    static final DefaultIdleCallbackExceptionReporter INSTANCE =
            new DefaultIdleCallbackExceptionReporter();

    private DefaultIdleCallbackExceptionReporter() {}

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
            // Final reporting boundary. A broken hook cannot strand the idle scheduler.
        }
    }
}
