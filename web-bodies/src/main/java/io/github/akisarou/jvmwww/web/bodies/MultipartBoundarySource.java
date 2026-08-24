package io.github.akisarou.jvmwww.web.bodies;

/** Supplies a fresh multipart/form-data boundary for each body extraction. */
public interface MultipartBoundarySource {
    String nextBoundary();
}
