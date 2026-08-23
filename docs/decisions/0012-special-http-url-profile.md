# 0012 — Add a special HTTP(S) URL record before general host parsing

Status: accepted for the second `web-url` slice.

## Context

Decision 0011 added exact `URLSearchParams` tuple and form-encoding behavior together with a
package-private update hook for a future live URL association. Fetch and reached libraries now need
canonical URL components, relative resolution, and a persistent `searchParams` object. Delegating
those operations to `java.net.URL` or `java.net.URI` would introduce a different parser, resolver,
normalizer, escaping policy, and mutability model.

The complete WHATWG parser also includes IDNA, IPv6, legacy numeric IPv4 syntax, `file:` URLs,
non-special schemes, opaque paths, and several validation-error recovery paths. Implementing those
shapes approximately would be less reliable than naming a strict reached profile and rejecting the
rest.

## Decision

Add an owner-confined `URL` class backed by one mutable internal URL record. The reached public ABI
provides:

```text
URL(RuntimeInstance, input)
URL(RuntimeInstance, input, baseString)
URL(RuntimeInstance, input, baseURL)
parse / canParse
href / origin / protocol
username / password
host / hostname / port
pathname / search / searchParams / hash
toString / toJSON
```

This slice accepts special `http:` and `https:` URLs only. It parses absolute authorities and resolves
relative path, query, fragment, and scheme-relative references against another reached HTTP(S) URL.
Scheme and ASCII host case is canonicalized, default ports are omitted, credentials use the userinfo
percent-encode set, special backslashes are path separators, dot and percent-encoded-dot segments are
removed, and path/query/fragment components use their selected WHATWG UTF-8 percent-encode sets.

The host profile is deliberately explicit:

- ASCII DNS-style labels are accepted and lower-cased;
- a four-piece shortest-decimal IPv4 address is accepted and serialized canonically;
- IDNA/non-ASCII hosts, IPv6, legacy short/octal/hexadecimal IPv4, numeric-ending domains that would
  enter the legacy IPv4 parser, percent-encoded hosts, and non-DNS ASCII host forms are rejected.

Constructor and `href` parse failures throw `JsTypeError`. Static `parse` returns null and
`canParse` returns false. Component setters apply valid reached values atomically; invalid or
unreached setter input leaves the existing component unchanged, matching the non-throwing URL setter
boundary.

## Live query object

Each `URL` creates one `URLSearchParams` and retains it for the lifetime of the URL object. Setting
`href` or `search` rebuilds that same object's tuple list without firing its update callback.
Mutating the query object form-serializes its list back into the URL query; an empty serialization
sets the query to null. This preserves object identity and avoids a resolver or observer wrapper.

## Ownership and allocation

`URL`, its record, path list, and associated query object are owner-confined to one
`RuntimeInstance`. Parsing and setters execute synchronously during an active host turn or
microtask. There is no worker parser, process-global cache, Future, coroutine, executor, Handler, or
Runnable. UTF-8 conversion uses `web-encoding`; `web-url` does not retain a JVM charset wrapper.

The internal record stores specialized component fields and an ordered path-segment list. It does not
round-trip through a Java URL string for every getter or setter. Path parsing reuses one `TextEncoder`
for all segments in that operation.

## Rejected alternatives

- `java.net.URL` or `URI`: different parsing, resolution, host, escaping, and mutation semantics.
- accepting all schemes while implementing only hierarchical HTTP behavior: opaque and non-special
  URLs have observably different state machines.
- Java IDN as an unqualified host substitute: WHATWG uses UTS 46 processing and explicit failure
  behavior that needs its own evidence.
- silently accepting IPv6 or legacy IPv4 through a platform parser: host representation and
  serialization would escape the semantic module.
- allocating a new `URLSearchParams` after every `href` or `search` assignment: breaks public object
  identity and the accepted update-hook design.

## Required evidence

Permanent Java 8 tests and structural gates prove:

- absolute canonicalization, credentials, default ports, dot segments, and component encoding;
- relative, scheme-relative, query-only, fragment-only, empty, and backslash resolution;
- valid component getters/setters and atomic refusal of invalid setter input;
- persistent two-way `URLSearchParams` association, including empty-query removal;
- empty query/fragment marker serialization and Unicode path scalar conversion;
- strict decimal IPv4 behavior and stable refusal of IDNA, IPv6, legacy IPv4, numeric-ending domains,
  and unsupported schemes;
- `parse`, `canParse`, `href` replacement, owner confinement, and runtime isolation;
- absence of java.net URL/URI/form helpers, java.nio charset wrappers, generic maps, Android,
  schedulers, futures, coroutines, and Runnable jobs from the semantic module.
