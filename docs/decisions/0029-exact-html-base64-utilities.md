# 0029 — Implement HTML Base64 utilities with exact-size primitive buffers

Status: accepted for the Web Mobile base64 utility profile.

## Context

Mobile Web and React-oriented libraries frequently use the historical `atob` and `btoa` globals.
They are not UTF-8 helpers and they are not interchangeable with Java's Base64 convenience APIs.
HTML defines `btoa` over a DOMString whose code units must all fit in one byte, and defines `atob`
through Infra's forgiving-base64 decoder. The latter removes only ASCII whitespace, has a precise
optional-padding rule, rejects the URL-safe alphabet, and deliberately discards unused low bits in
the final quantum.

`java.util.Base64.getDecoder()` rejects whitespace that HTML accepts. Its MIME decoder ignores a
broader set of non-alphabet bytes than HTML permits. The URL decoder accepts a different alphabet.
Choosing among them and adding pre/post-processing would still require a separate normalized input
String or byte array and would leave invalid-input behavior dependent on a Java library rather than
the Web contract.

The Native TypeScript profile already has JavaScript strings and exact DOMException values. It does
not need a RuntimeInstance, host task, typed-array carrier, or platform service for these synchronous
functions.

## Decision

Add two Java 8 modules:

```text
web-base64
web-base64-testkit
```

The production module exposes one stateless compiler-facing class:

```java
Base64Utilities.btoa(String data)
Base64Utilities.atob(String data)
```

A Java null argument represents Web IDL's `String(null)` result, `"null"`. Checked generated code
owns every other dynamic `DOMString` conversion. The module does not call `Object.toString()` or
invent Java conversion semantics for numbers, booleans, symbols, or user objects.

### `btoa`

`btoa` first scans the complete UTF-16 String. If any code unit is greater than U+00FF it throws a
`DOMException` named `InvalidCharacterError`, before allocating the output buffer.

For valid input it computes the padded output length using `long`, rejects a result beyond Java's
indexed String limit, allocates one exact `char[]`, and writes RFC 4648 alphabet characters and `=`
padding directly. The alphabet is selected by primitive arithmetic rather than a retained lookup
array. Constructing the returned Java String necessarily copies that temporary character array on
the Java 8 surface; no additional byte array, builder, stream, or encoded wrapper exists.

### `atob`

`atob` performs two linear scans.

The first scan skips exactly U+0009, U+000A, U+000C, U+000D, and U+0020, counts the logical input,
records its last two non-whitespace code units, applies Infra's one-or-two trailing `=` removal only
when the cleaned length is divisible by four, rejects a remaining length congruent to one modulo
four, and computes the exact output length.

The second scan validates and decodes the logical data directly into one exact `char[]`. Accepted
symbols are ASCII alphanumeric, `+`, and `/`; any `=` before the logical end, URL-safe `-`/`_`,
non-ASCII whitespace, or other code unit throws `InvalidCharacterError`. Complete quartets emit three
characters in U+0000–U+00FF. Final two- or three-sextet groups emit one or two characters and ignore
the unused low bits exactly as Infra specifies. Whitespace-only and empty inputs return the shared
empty String.

The result is HTML's binary string/ByteString representation. This operation does not perform UTF-8
encoding or decoding; callers needing Unicode bytes use the Encoding API before or after a typed
byte boundary.

### Failure and ownership

Both invalid paths create `DOMException(message, "InvalidCharacterError")`, which also carries the
legacy code 5 through the existing Web events module. Error messages are stable diagnostics but are
not treated as a cross-engine conformance surface.

The class has no mutable static state and no RuntimeInstance. All arguments and results are ordinary
synchronous language values. The implementation creates no task, Promise, callback, lock, atomic,
or lifecycle registration.

## Performance consequences

For non-empty valid input, each operation is O(n), scans at most twice, and allocates one exactly
sized temporary `char[]` plus the required returned String. `atob` never creates a whitespace-stripped
String or intermediate byte array. `btoa` never creates a byte representation of the input. No
collection, token, per-quantum object, regex matcher, stream, charset wrapper, or Base64 encoder /
decoder object enters either path.

The empty-input path returns before buffer allocation. Invalid `btoa` rejects before allocating a
potentially large output. Slot-free static methods make repeated calls independent and avoid
per-runtime capability state.

## Profile limits

- Full JavaScript `String()` conversion for non-string dynamic values remains compiler-owned. The
  Java method only defines the exact String and null crossings.
- `Uint8Array.fromBase64`, `Uint8Array.prototype.toBase64`, streaming Base64, URL-safe Base64, and
  data-URL parsing are separate APIs with different return types and options.
- Java String and array indices are signed 32-bit. An otherwise valid `btoa` result beyond that limit
  fails explicitly rather than overflowing its output-length calculation.
- These helpers preserve historical binary-string semantics; they are not recommended as a hidden
  Unicode transport.

## Required evidence

Permanent Java 8 conformance and structural gates prove:

- RFC 4648 encode vectors, embedded NULL, all 256 byte-valued code units, padding, and round trips;
- rejection of U+0100, lone surrogates, and supplementary code points before output allocation;
- padded and unpadded forgiving decode, exact ASCII whitespace, discarded final bits, and binary
  output code units;
- every valid and invalid case in WPT's committed `fetch/data-urls/resources/base64.json` corpus;
- 10,000 deterministic randomized byte strings against a test-only independent Java RFC encoder,
  with padded, unpadded, and whitespace-interleaved decode paths;
- exact `InvalidCharacterError` name and legacy code;
- one production character-array allocation in each non-empty method and no byte-array or object
  array allocation;
- no generated inner adapter, mutable static table, normalized String, Java Base64 API, regex,
  stream, charset wrapper, generic collection, runtime owner, scheduler, Android API, reflection, or
  networking dependency in production bytecode.
