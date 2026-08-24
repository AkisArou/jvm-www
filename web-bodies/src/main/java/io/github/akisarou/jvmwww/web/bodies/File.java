package io.github.akisarou.jvmwww.web.bodies;

import io.github.akisarou.jvmwww.runtime.RuntimeInstance;
import io.github.akisarou.jvmwww.web.encoding.Utf8Codec;
import java.util.Objects;

/** Immutable File metadata layered on immutable Blob bytes. */
public final class File extends Blob {
    private final String name;
    private final long lastModified;

    public File(RuntimeInstance runtime, byte[] bytes, String name) {
        this(runtime, bytes, name, "", System.currentTimeMillis());
    }

    public File(RuntimeInstance runtime, byte[] bytes, String name, String type) {
        this(runtime, bytes, name, type, System.currentTimeMillis());
    }

    public File(
            RuntimeInstance runtime,
            byte[] bytes,
            String name,
            String type,
            long lastModified) {
        this(
                runtime,
                BlobData.singleOwned(Objects.requireNonNull(bytes, "bytes").clone()),
                type,
                name,
                lastModified);
    }

    public File(RuntimeInstance runtime, String text, String name) {
        this(runtime, text, name, "", System.currentTimeMillis());
    }

    public File(
            RuntimeInstance runtime,
            String text,
            String name,
            String type,
            long lastModified) {
        this(
                runtime,
                BlobData.singleOwned(Utf8Codec.encode(Objects.requireNonNull(text, "text"))),
                type,
                name,
                lastModified);
    }

    File(
            RuntimeInstance runtime,
            BlobData data,
            String type,
            String name,
            long lastModified) {
        super(runtime, data, type);
        this.name = BodyScalar.fromString(name, "name");
        this.lastModified = lastModified;
    }

    static File fromBlob(Blob source, String name) {
        source.assertAccess();
        return new File(
                source.getRuntime(),
                source.data(),
                source.getType(),
                name,
                System.currentTimeMillis());
    }

    public String getName() {
        assertAccess();
        return name;
    }

    public long getLastModified() {
        assertAccess();
        return lastModified;
    }

    public String getWebkitRelativePath() {
        assertAccess();
        return "";
    }
}
