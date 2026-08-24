package io.github.akisarou.jvmwww.web.base64.testkit;

import io.github.akisarou.jvmwww.web.base64.Base64Utilities;
import io.github.akisarou.jvmwww.web.events.DOMException;

/** Exact copy of WPT's forgiving-base64 data corpus at commit 229083b8. */
public final class Base64WptConformance {
    private Base64WptConformance() {}

    public static void main(String[] args) {
        valid("", new int[] {}, 0);
        valid("abcd", new int[] {105, 183, 29}, 1);
        valid(" abcd", new int[] {105, 183, 29}, 2);
        valid("abcd ", new int[] {105, 183, 29}, 3);
        invalid(" abcd===", 4);
        invalid("abcd=== ", 5);
        invalid("abcd ===", 6);
        invalid("a", 7);
        valid("ab", new int[] {105}, 8);
        valid("abc", new int[] {105, 183}, 9);
        invalid("abcde", 10);
        invalid("\ud800\udc00", 11);
        invalid("=", 12);
        invalid("==", 13);
        invalid("===", 14);
        invalid("====", 15);
        invalid("=====", 16);
        invalid("a=", 17);
        invalid("a==", 18);
        invalid("a===", 19);
        invalid("a====", 20);
        invalid("a=====", 21);
        invalid("ab=", 22);
        valid("ab==", new int[] {105}, 23);
        invalid("ab===", 24);
        invalid("ab====", 25);
        invalid("ab=====", 26);
        valid("abc=", new int[] {105, 183}, 27);
        invalid("abc==", 28);
        invalid("abc===", 29);
        invalid("abc====", 30);
        invalid("abc=====", 31);
        invalid("abcd=", 32);
        invalid("abcd==", 33);
        invalid("abcd===", 34);
        invalid("abcd====", 35);
        invalid("abcd=====", 36);
        invalid("abcde=", 37);
        invalid("abcde==", 38);
        invalid("abcde===", 39);
        invalid("abcde====", 40);
        invalid("abcde=====", 41);
        invalid("=a", 42);
        invalid("=a=", 43);
        invalid("a=b", 44);
        invalid("a=b=", 45);
        invalid("ab=c", 46);
        invalid("ab=c=", 47);
        invalid("abc=d", 48);
        invalid("abc=d=", 49);
        invalid("ab\u000bcd", 50);
        invalid("ab\u3000cd", 51);
        invalid("ab\u3001cd", 52);
        valid("ab\tcd", new int[] {105, 183, 29}, 53);
        valid("ab\ncd", new int[] {105, 183, 29}, 54);
        valid("ab\fcd", new int[] {105, 183, 29}, 55);
        valid("ab\rcd", new int[] {105, 183, 29}, 56);
        valid("ab cd", new int[] {105, 183, 29}, 57);
        invalid("ab\u00a0cd", 58);
        valid("ab\t\n\f\r cd", new int[] {105, 183, 29}, 59);
        valid(" \t\n\f\r ab\t\n\f\r cd\t\n\f\r ", new int[] {105, 183, 29}, 60);
        valid("ab\t\n\f\r =\t\n\f\r =\t\n\f\r ", new int[] {105}, 61);
        invalid("A", 62);
        valid("/A", new int[] {252}, 63);
        valid("//A", new int[] {255, 240}, 64);
        valid("///A", new int[] {255, 255, 192}, 65);
        invalid("////A", 66);
        invalid("/", 67);
        valid("A/", new int[] {3}, 68);
        valid("AA/", new int[] {0, 15}, 69);
        invalid("AAAA/", 70);
        valid("AAA/", new int[] {0, 0, 63}, 71);
        invalid("\u0000nonsense", 72);
        invalid("abcd\u0000nonsense", 73);
        valid("YQ", new int[] {97}, 74);
        valid("YR", new int[] {97}, 75);
        invalid("~~", 76);
        invalid("..", 77);
        invalid("--", 78);
        invalid("__", 79);
        System.out.println("WPT forgiving-base64 corpus: 80 cases passed");
    }

    private static void valid(String input, int[] expected, int caseNumber) {
        String actual = Base64Utilities.atob(input);
        if (actual.length() != expected.length) {
            throw new AssertionError(
                    "WPT case " + caseNumber + " length: expected " + expected.length
                            + ", got " + actual.length());
        }
        for (int i = 0; i < expected.length; i++) {
            if (actual.charAt(i) != expected[i]) {
                throw new AssertionError(
                        "WPT case " + caseNumber + " byte " + i + ": expected "
                                + expected[i] + ", got " + (int) actual.charAt(i));
            }
        }
    }

    private static void invalid(String input, int caseNumber) {
        try {
            Base64Utilities.atob(input);
        } catch (DOMException expected) {
            if ("InvalidCharacterError".equals(expected.getName())) {
                return;
            }
        }
        throw new AssertionError("WPT case " + caseNumber + " accepted invalid input");
    }
}
