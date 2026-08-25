package io.github.akisarou.jvmwww.web.nativeelements;

/**
 * One-call primitive publication boundary for a related native element identity.
 *
 * <p>The host may call {@link #setRelatedElement(long)} exactly once before returning
 * {@code true} from the corresponding relation read. A relation read returning {@code false}
 * must not write the sink. The host must not retain the sink after the synchronous call.</p>
 */
public interface NativeElementRelationSink {
    void setRelatedElement(long elementIdentity);
}
