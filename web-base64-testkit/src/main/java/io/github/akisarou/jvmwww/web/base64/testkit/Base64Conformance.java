package io.github.akisarou.jvmwww.web.base64.testkit;

import io.github.akisarou.jvmwww.web.base64.Base64Utilities;
import io.github.akisarou.jvmwww.web.events.DOMException;
import java.util.Random;

/** Deterministic Java 8 conformance for HTML atob/btoa and Infra forgiving-base64. */
public final class Base64Conformance {
    private int passed;

    public static void main(String[] args) {
        new Base64Conformance().run();
    }

    private void run() {
        knownEncodeVectors();
        binaryRoundTrip();
        invalidEncodeCodeUnits();
        forgivingDecodeVectors();
        asciiWhitespace();
        paddingAndDiscardedBits();
        invalidDecodeInputs();
        nullDomStringConversion();
        randomizedReferenceComparison();
        System.out.println("Base64 conformance: " + passed + " tests passed");
    }

    private void knownEncodeVectors() {
        eq("", Base64Utilities.btoa(""), "empty");
        eq("Zg==", Base64Utilities.btoa("f"), "f");
        eq("Zm8=", Base64Utilities.btoa("fo"), "fo");
        eq("Zm9v", Base64Utilities.btoa("foo"), "foo");
        eq("Zm9vYg==", Base64Utilities.btoa("foob"), "foob");
        eq("Zm9vYmE=", Base64Utilities.btoa("fooba"), "fooba");
        eq("Zm9vYmFy", Base64Utilities.btoa("foobar"), "foobar");
        eq("AGE=", Base64Utilities.btoa("\u0000a"), "leading NUL");
        eq("YQBi", Base64Utilities.btoa("a\u0000b"), "embedded NUL");
        pass();
    }

    private void binaryRoundTrip() {
        char[] all = new char[256];
        for (int i = 0; i < all.length; i++) {
            all[i] = (char) i;
        }
        String binary = new String(all);
        String encoded = Base64Utilities.btoa(binary);
        eq(binary, Base64Utilities.atob(encoded), "all byte values round trip");
        pass();
    }

    private void invalidEncodeCodeUnits() {
        invalidBtoa("\u0100");
        invalidBtoa("a\u0100b");
        invalidBtoa("\ud800");
        invalidBtoa("\udc00");
        invalidBtoa("\ud83d\ude00");
        pass();
    }

    private void forgivingDecodeVectors() {
        eq("", Base64Utilities.atob(""), "empty decode");
        eq("f", Base64Utilities.atob("Zg=="), "padded one byte");
        eq("f", Base64Utilities.atob("Zg"), "unpadded one byte");
        eq("fo", Base64Utilities.atob("Zm8="), "padded two bytes");
        eq("fo", Base64Utilities.atob("Zm8"), "unpadded two bytes");
        eq("foo", Base64Utilities.atob("Zm9v"), "complete quartet");
        eq("foobar", Base64Utilities.atob("Zm9vYmFy"), "multiple quartets");
        pass();
    }

    private void asciiWhitespace() {
        eq("foo", Base64Utilities.atob(" Z\tm\n9\fv\r "), "all ASCII whitespace");
        eq("foo", Base64Utilities.atob("Z m 9 v"), "interior spaces");
        eq("f", Base64Utilities.atob(" Z g = = \n"), "whitespace around padding");
        eq("", Base64Utilities.atob(" \t\n\f\r "), "whitespace only");
        invalidAtob("Z\u000bm9v");
        invalidAtob("Z\u00a0m9v");
        pass();
    }

    private void paddingAndDiscardedBits() {
        eq("a", Base64Utilities.atob("YQ=="), "two padding");
        eq("ab", Base64Utilities.atob("YWI="), "one padding");
        eq("a", Base64Utilities.atob("YQ"), "missing two padding accepted");
        eq("ab", Base64Utilities.atob("YWI"), "missing one padding accepted");
        eq("a", Base64Utilities.atob("YR"), "discarded four bits");
        eq("a", Base64Utilities.atob("Yf"), "discarded four high variants");
        eq("ab", Base64Utilities.atob("YWJ"), "discarded two bits 1");
        eq("ab", Base64Utilities.atob("YWK"), "discarded two bits 2");
        eq("ab", Base64Utilities.atob("YWL"), "discarded two bits 3");
        pass();
    }

    private void invalidDecodeInputs() {
        String[] invalid = {
            "A", "AAAAA", "YQ=", "YQ===", "YQ====", "=YQ=", "Y=Q=",
            "YQ==A", "YQ==A===", "YWJj=", "====", "A===", "-", "_",
            "YW-J", "YW_J", "\u00e9", "\u0000"
        };
        for (String value : invalid) {
            invalidAtob(value);
        }
        pass();
    }

    private void nullDomStringConversion() {
        eq("bnVsbA==", Base64Utilities.btoa(null), "btoa null becomes DOMString null");
        String decoded = Base64Utilities.atob(null);
        code(0x9e, decoded.charAt(0), "atob null byte 0");
        code(0xe9, decoded.charAt(1), "atob null byte 1");
        code(0x65, decoded.charAt(2), "atob null byte 2");
        pass();
    }

    private void randomizedReferenceComparison() {
        Random random = new Random(0x6a766d777777L);
        for (int test = 0; test < 10000; test++) {
            int length = random.nextInt(129);
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            String binary = binaryString(bytes);
            String expected = java.util.Base64.getEncoder().encodeToString(bytes);
            String encoded = Base64Utilities.btoa(binary);
            eq(expected, encoded, "random encode " + test);
            eq(binary, Base64Utilities.atob(encoded), "random padded decode " + test);

            int end = encoded.length();
            while (end > 0 && encoded.charAt(end - 1) == '=') {
                end--;
            }
            eq(binary, Base64Utilities.atob(encoded.substring(0, end)),
                    "random unpadded decode " + test);
            eq(binary, Base64Utilities.atob(interleaveWhitespace(encoded)),
                    "random whitespace decode " + test);
        }
        pass();
    }

    private static String binaryString(byte[] bytes) {
        char[] chars = new char[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            chars[i] = (char) (bytes[i] & 0xff);
        }
        return new String(chars);
    }

    private static String interleaveWhitespace(String value) {
        if (value.isEmpty()) {
            return " \n";
        }
        StringBuilder result = new StringBuilder(value.length() * 2 + 2);
        result.append('\t');
        for (int i = 0; i < value.length(); i++) {
            result.append(value.charAt(i));
            if ((i & 1) == 0) {
                result.append(i % 3 == 0 ? '\n' : ' ');
            }
        }
        result.append('\r');
        return result.toString();
    }

    private static void invalidBtoa(String value) {
        try {
            Base64Utilities.btoa(value);
        } catch (DOMException expected) {
            domInvalidCharacter(expected, "btoa invalid");
            return;
        }
        throw new AssertionError("btoa accepted invalid code units");
    }

    private static void invalidAtob(String value) {
        try {
            Base64Utilities.atob(value);
        } catch (DOMException expected) {
            domInvalidCharacter(expected, "atob invalid " + printable(value));
            return;
        }
        throw new AssertionError("atob accepted invalid input: " + printable(value));
    }

    private static void domInvalidCharacter(DOMException error, String label) {
        eq("InvalidCharacterError", error.getName(), label + " name");
        if (error.getCode() != DOMException.INVALID_CHARACTER_ERR) {
            throw new AssertionError(label + " code: " + error.getCode());
        }
    }

    private static String printable(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c <= 0x7e) {
                result.append(c);
            } else {
                result.append("\\u");
                String hex = Integer.toHexString(c);
                for (int j = hex.length(); j < 4; j++) result.append('0');
                result.append(hex);
            }
        }
        return result.toString();
    }

    private void pass() { passed++; }

    private static void eq(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + printable(expected)
                    + ", got " + printable(actual));
        }
    }

    private static void code(int expected, char actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + (int) actual);
        }
    }
}
