package io.github.akisarou.jvmwww.runtime;

/**
 * Allocation-backed carrier used when generated Java must throw an arbitrary JavaScript value.
 *
 * <p>This is control flow, not the JavaScript {@code Error} value itself, so it intentionally does
 * not capture a Java stack trace. Checked lowering can avoid this object on statically direct
 * rejection edges and use it only where JVM exception unwinding is required.</p>
 */
public final class JsThrownValue extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int payloadKind;
    private final double numberPayload;
    private final boolean booleanPayload;
    private final transient Object referencePayload;

    private JsThrownValue(
            int payloadKind,
            double numberPayload,
            boolean booleanPayload,
            Object referencePayload) {
        super(null, null, false, false);
        this.payloadKind = payloadKind;
        this.numberPayload = numberPayload;
        this.booleanPayload = booleanPayload;
        this.referencePayload = referencePayload;
    }

    public static JsThrownValue voidValue() {
        return new JsThrownValue(JsPromise.PAYLOAD_VOID, 0.0, false, null);
    }

    public static JsThrownValue number(double value) {
        return new JsThrownValue(JsPromise.PAYLOAD_NUMBER, value, false, null);
    }

    public static JsThrownValue bool(boolean value) {
        return new JsThrownValue(JsPromise.PAYLOAD_BOOLEAN, 0.0, value, null);
    }

    public static JsThrownValue reference(Object value) {
        return new JsThrownValue(JsPromise.PAYLOAD_REFERENCE, 0.0, false, value);
    }

    public int getPayloadKind() {
        return payloadKind;
    }

    public double getNumberPayload() {
        requireKind(JsPromise.PAYLOAD_NUMBER);
        return numberPayload;
    }

    public boolean getBooleanPayload() {
        requireKind(JsPromise.PAYLOAD_BOOLEAN);
        return booleanPayload;
    }

    public Object getReferencePayload() {
        requireKind(JsPromise.PAYLOAD_REFERENCE);
        return referencePayload;
    }

    private void requireKind(int expected) {
        if (payloadKind != expected) {
            throw new IllegalStateException(
                    "Thrown value payload kind " + payloadKind + " is not " + expected);
        }
    }
}
