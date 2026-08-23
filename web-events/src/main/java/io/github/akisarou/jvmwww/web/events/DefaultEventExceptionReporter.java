package io.github.akisarou.jvmwww.web.events;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;

/** Default host reporter used when a capability does not install a richer error projection. */
final class DefaultEventExceptionReporter implements EventExceptionReporter {
    static final DefaultEventExceptionReporter INSTANCE = new DefaultEventExceptionReporter();

    private DefaultEventExceptionReporter() {}

    @Override
    public void report(RuntimeInstance runtime, EventFailurePhase phase, Throwable error) {
        Thread thread = Thread.currentThread();
        Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
        if (handler == null) {
            handler = Thread.getDefaultUncaughtExceptionHandler();
        }
        if (handler != null) {
            try {
                handler.uncaughtException(thread, error);
            } catch (ThreadDeath fatal) {
                throw fatal;
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (LinkageError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Reporting is terminal at this boundary. A broken host hook must not corrupt the
                // listener list or prevent later listeners from running.
            }
        }
    }
}
