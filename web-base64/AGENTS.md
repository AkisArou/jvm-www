# Web Base64 rules

- `web-base64` implements HTML `atob` and `btoa`, not a generic RFC, MIME, URL-safe, or Unicode codec.
- `btoa` validates every UTF-16 code unit before allocating output and rejects values above U+00FF with `InvalidCharacterError`.
- `atob` follows Infra forgiving-base64 exactly: remove only ASCII whitespace, apply the precise trailing-padding rule, reject remainder one and every non-alphabet code point, and ignore unused low bits in the final quantum.
- Decode directly into the returned binary string representation. Do not introduce an intermediate byte array, token list, whitespace-normalized string, or per-quantum object.
- The common success path may allocate only one exactly sized temporary `char[]` plus the specification-visible Java `String`. Empty input may return the shared empty String.
- Do not use `java.util.Base64`: its basic, URL, and MIME decoders have different whitespace and invalid-input behavior. Also exclude regex, streams, charset wrappers, collections, schedulers, Android APIs, and runtime ownership.
- Java `null` represents Web IDL `String(null)` and therefore becomes `"null"`. Dynamic conversion of other JavaScript values remains a compiler/profile boundary; never substitute Java `Object.toString()`.
- Keep the WPT forgiving-base64 corpus and randomized independent reference checks in the permanent gate.

Run `./scripts/test-base64.sh` before every change.
