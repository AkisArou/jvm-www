package io.github.akisarou.jvmwww.web.console;

/**
 * Compiler/profile boundary for Console Standard format-specifier conversions.
 *
 * <p>Generated bindings may supply exact ECMAScript String/parseInt/parseFloat conversions. The
 * default implementation covers ordinary Java representations used by the current static profile.</p>
 */
public interface ConsoleValueFormatter {
    String toStringValue(Object value);

    String toIntegerString(Object value);

    String toFloatString(Object value);

    String formatObject(Object value, boolean generic);
}
