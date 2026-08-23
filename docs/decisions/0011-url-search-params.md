# 0011 — Own URLSearchParams list and form encoding semantics

Status: accepted for the first `web-url` slice.

## Context

The runtime now has exact UTF-8 Encoding objects, Fetch, and Console, but packages increasingly need
`URLSearchParams` before a complete URL parser is reached. Java's `URLEncoder`, `URLDecoder`, URI,
and URL classes do not define the WHATWG `application/x-www-form-urlencoded` parser, serializer,
USVString conversion, duplicate ordering, optional-value deletion, or live URL query behavior.

A seemingly small substitution would be observable: spaces serialize as `+`, literal plus signs are
percent-encoded, malformed percent escapes remain literal, malformed UTF-8 uses the Encoding
replacement algorithm, lone UTF-16 surrogates become U+FFFD, and sorting is stable over UTF-16 code
units rather than locale or Unicode collation.

## Decision

Add `web-url` with an owner-confined `URLSearchParams`. Its first public profile supports:

```text
URLSearchParams(RuntimeInstance)
URLSearchParams(RuntimeInstance, String)
URLSearchParams(URLSearchParams)
size
append
get / getAll
has(name) / has(name, value)
delete(name) / delete(name, value)
set
stable sort
ordered getName/getValue compiler ABI
toString
```

The string constructor removes one leading `?`, parses `application/x-www-form-urlencoded` bytes,
skips empty `&` sequences, splits each non-empty sequence at its first `=`, converts `+` to space,
percent-decodes valid hexadecimal triplets, and UTF-8 decodes without stripping a BOM. Invalid
percent triplets stay literal and malformed UTF-8 follows replacement decoding.

All names and values are scalar value strings. Public inputs convert lone UTF-16 surrogates to
U+FFFD before storage. Pairs remain in insertion order. `set` updates the first matching tuple and
removes later duplicates; value-qualified `delete` and `has` compare both tuple fields. `sort` is
stable and compares names by UTF-16 code units.

Serialization uses the form percent-encode set: ASCII alphanumeric plus `*`, `-`, `.`, and `_`
remain literal, spaces become `+`, and every other UTF-8 byte becomes uppercase `%HH`.

## Future URL association

`URLSearchParams` has a package-private update target and a replacement path that does not notify.
The following URL slice can associate one persistent query object with a URL record. Mutations then
reserialize the URL query, while URL `href` or `search` replacement can rebuild the tuple list
without allocating a new public query object or recursing through its update callback.

This commit does not claim the `URL` constructor, host parsing, IDNA, IPv4/IPv6, relative resolution,
object URLs, or sequence/record constructor overloads. Those remain explicit later slices.

## Rejected alternatives

- `java.net.URLEncoder` or `URLDecoder`: different charset, plus, malformed-input, and API contracts.
- `java.net.URI` or `URL`: not the WHATWG parser or serializer.
- a generic `Map<String, String>`: loses duplicate pairs, order, value-qualified operations, and
  stable sort behavior while boxing table state.
- locale or code-point sorting: URLSearchParams sorts by UTF-16 code units.
- worker or process-global parameter state: Web object observation remains owner-confined per
  runtime.

## Required evidence

Permanent Java 8 tests and structural gates prove:

- leading `?`, empty sequence, first-`=`, plus, and percent-decoding behavior;
- invalid percent escapes and malformed UTF-8 replacement boundaries;
- BOM preservation during form decoding;
- duplicate ordering, `set`, both `delete`/`has` forms, and independent copying;
- exact form serialization and lone-surrogate conversion;
- stable UTF-16 code-unit sorting;
- owner confinement;
- absence of java.net URL/URI/form helpers, java.nio charset wrappers, generic maps, Android,
  schedulers, futures, coroutines, and Runnable jobs from the semantic module.
