# 0008 — Own exact UTF-8 Encoding API state instead of JVM charset convenience behavior

Status: accepted for the first `web-encoding` slice.

## Context

The Web Mobile profile needs `TextEncoder`, `TextDecoder`, and exact UTF-8 behavior for Fetch body
consumption. Java exposes UTF-8 helpers, but those helpers are not the semantic authority. In
particular, a default `CharsetEncoder` may replace malformed UTF-16 with `?` rather than U+FFFD,
convenience decoding does not own the Encoding API's BOM state, and a generic decoder wrapper does
not by itself define streaming, fatal errors, or `encodeInto` progress.

The runtime also cannot move a stateful decoder to a worker or process-global cache. Decoder state is
language-visible through successive `decode(..., {stream:true})` calls and therefore belongs to one
`RuntimeInstance` owner.

## Decision

Add `web-encoding` with:

```text
TextEncoder
TextEncoderEncodeIntoResult
TextDecoder
TextDecoderOptions
TextDecodeOptions
```

The first decoder profile supports the WHATWG UTF-8 labels `utf-8`, `utf8`, and
`unicode-1-1-utf-8`, after ASCII-whitespace trimming and ASCII case folding. Other labels are
rejected with the runtime's `JsRangeError`; they are not delegated to the JDK under approximately
matching names. Legacy encodings remain later, independently tested slices.

Encoding objects retain their `RuntimeInstance` and require an active owner host turn or microtask.
`TextEncoder` and `TextDecoder` are ordinary language values, not `RuntimeTask`, `Runnable`, Future,
or coroutine objects.

## TextEncoder

`TextEncoder.encode` converts Java UTF-16 code units to Unicode scalar values directly. A valid
surrogate pair becomes one supplementary scalar. Every lone leading or trailing surrogate becomes
U+FFFD and is emitted as `EF BF BD`.

The implementation first computes the exact output size and then writes directly into one `byte[]`.
It does not call `String.getBytes` or allocate a charset encoder.

`encodeInto` writes only complete UTF-8 sequences. If the destination cannot hold the next scalar,
that scalar is neither consumed nor partially written. Its result reports source progress in UTF-16
code units, so a supplementary scalar increments `read` by two.

## TextDecoder

Each `TextDecoder` stores the UTF-8 decoder state required by the Encoding Standard:

```text
code point
bytes seen
bytes needed
lower continuation boundary
upper continuation boundary
stream/do-not-flush state
BOM-seen state
```

Invalid continuation bytes reset the active sequence, emit one replacement in replacement mode,
and are then reprocessed as a possible new leading byte. This preserves the standard's maximal
subpart behavior rather than relying on a platform decoder's error grouping.

Replacement mode emits U+FFFD. Fatal mode throws `JsTypeError`. A streaming call preserves an
incomplete sequence; a later non-streaming call flushes it as one replacement or one fatal error.
BOM handling is applied to the first emitted scalar of each decode sequence: the initial U+FEFF is
stripped by default and preserved when `ignoreBOM` is true. The state resets when a new
non-streaming decode sequence begins.

Input bytes are consumed synchronously and are never retained. Only the compact decoder state
crosses calls, so later mutation of a caller's array cannot affect future decoding.

## Fetch integration

`Response.text()` keeps its one-shot body and microtask settlement contract from decision 0006, but
uses `TextDecoder` instead of `new String(..., UTF_8)`. Fetch and direct Encoding API calls therefore
share the same BOM, malformed-sequence, and replacement behavior.

`web-fetch-core` depends on `web-encoding`; `web-encoding` remains independent of Fetch, events,
Android, OkHttp, and platform schedulers.

## Rejected alternatives

- `String.getBytes(UTF_8)` for `TextEncoder`: malformed-surrogate replacement is not proven to be
  U+FFFD and `encodeInto` progress cannot be recovered without another pass or buffer wrapper.
- `new String(bytes, UTF_8)` for `TextDecoder`: it does not expose the Encoding API's fatal,
  streaming, or BOM state and its malformed-sequence grouping is not the normative contract.
- retaining `CharsetDecoder` between calls: this imports JDK error grouping and BOM behavior and
  adds a general charset object where the reached profile needs a compact UTF-8 state machine.
- accepting every JDK charset label: Java aliases and WHATWG labels differ, notably for legacy
  encodings, and silently creates false compatibility claims.
- process-global encoder or decoder caches: decoder state and runtime ownership are per object and
  per `RuntimeInstance`.
- worker-thread decoding for Fetch: body decoding is language-visible work and remains in the
  existing owner microtask.

## Required evidence

Permanent Java 8 tests and structural gates must prove:

- ASCII, BMP, supplementary, and lone-surrogate encoding;
- exact `encodeInto` read/written counts and no partial scalar writes;
- UTF-8 label normalization and stable rejection of unsupported labels;
- valid decoding plus replacement boundaries for invalid continuations, overlong forms, surrogate
  encodings, and out-of-range scalars;
- fatal `JsTypeError` behavior and decoder reuse after an error;
- initial BOM stripping, `ignoreBOM`, one-BOM-only behavior, and reset between decode sequences;
- multibyte and BOM sequences split across streaming calls;
- replacement and fatal behavior when an incomplete sequence is flushed;
- owner-turn confinement;
- Fetch text consumption using the same decoder;
- no `java.nio.charset`, `String.getBytes`, Future, coroutine, Android Handler, or `Runnable` path in
  `web-encoding`;
- Java 8 compilation with all warnings treated as errors.
