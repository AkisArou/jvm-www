package io.github.akisarou.jvmwww.web.nativeelements;

/**
 * Live same-object collection of one element's current direct element children.
 *
 * <p>The collection retains only the shared renderer context and the parent's opaque exact
 * identity. Length, indexed reads, and ID lookup resolve the renderer's current committed relation
 * snapshot each time. No child wrapper array or collection snapshot is retained.</p>
 */
public final class HTMLCollection {
    private static final double UNSIGNED_LONG_MODULUS = 4294967296.0;

    private final NativeElementContext context;
    private final long parentIdentity;

    HTMLCollection(NativeElementContext context, long parentIdentity) {
        this.context = context;
        this.parentIdentity = parentIdentity;
    }

    /** Returns the current number of direct element children. */
    public int getLength() {
        return context.getChildElementCount(parentIdentity);
    }

    /**
     * Returns the child selected by Web IDL unsigned-long conversion, or {@code null} when absent.
     */
    public ReactNativeElement item(double index) {
        long converted = toUnsignedLong(index);
        if (converted > Integer.MAX_VALUE) {
            context.assertAccess();
            return null;
        }
        return context.getElementChildAt(parentIdentity, (int) converted);
    }

    /**
     * Returns the first direct child whose reached ID equals {@code name}, in current tree order.
     *
     * <p>The selected React Native profile does not expose a separate HTML {@code name} attribute;
     * therefore the collection's supported names are its non-empty element IDs. Java {@code null}
     * follows Web IDL DOMString conversion and is searched as the string {@code "null"}.</p>
     */
    public ReactNativeElement namedItem(String name) {
        return context.getNamedElementChild(parentIdentity, name == null ? "null" : name);
    }

    private static long toUnsignedLong(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value == 0.0) {
            return 0L;
        }
        double integer = value < 0.0 ? Math.ceil(value) : Math.floor(value);
        double modulo = integer % UNSIGNED_LONG_MODULUS;
        if (modulo < 0.0) {
            modulo += UNSIGNED_LONG_MODULUS;
        }
        return (long) modulo;
    }
}
