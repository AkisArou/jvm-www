package io.github.akisarou.jvmwww.web.bodies;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;

/** Owner-confined source that can publish one immutable buffered body snapshot. */
public interface BufferedBodySource {
    RuntimeInstance getRuntime();
    BufferedBodySnapshot snapshot();
}
