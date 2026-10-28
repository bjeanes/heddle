# 0005. Answer `initialize` itself, and register features later

Status: Accepted, 2026-10-25. Amended by
[0006](0006-start-backends-lazily-with-eager-as-a-flag.md),
[0017](0017-send-changes-in-each-backends-sync-kind.md) and
[0020](0020-register-features-with-absolute-globs-one-request-each.md).

## Context

A client sends `initialize` and waits for the server's capabilities before
sending anything else. heddle has to answer before it knows which backends it
will need. Positions in LSP count UTF-16 code units unless the two sides agree
otherwise.

## Options considered

- **Start the first matching backend** and answer with its capabilities.
- **Announce a fixed union** of features at startup, answering empty for files
  whose server lacks one.
- **Register each backend's features as it starts**, limited to its files,
  falling back to announcing them where the client can't register later.
- **One registration per backend, or one per feature** covering every backend
  that offers it.

## Decision

heddle answers `initialize` itself. It asks for the full text on every change,
which spares it applying edits by position. Since it offers only UTF-16, to the
client and to every backend, it never converts positions either ([Position
encoding]). As backends start, it registers their features with document
selectors ([`1e5f81c`]). Each feature gets one registration covering every
backend's files ([`0a80203`]). With one registration per backend, VS Code would
make a provider for each. It would then send each request, and show each answer,
once per backend. heddle narrows the selectors in a backend's own registrations
to its files ([`35e571e`]). Workspace symbol search is announced at startup,
since it isn't about any one file ([`68ceecd`]). See [Startup and capabilities].

## Consequences

Semantic tokens and on-type formatting can't be announced before a backend
supplies their options. The spec doesn't say whether registering an ID again
replaces it, so heddle unregisters first. Features a backend registers itself
still go through once per backend. Keeping each file's full text later let
heddle replay open files to a backend that starts late ([`1acf47e`]).

[`1e5f81c`]: https://github.com/bjeanes/heddle/commit/1e5f81cde7391f17618337c50f135ae94d8ac071
[`0a80203`]: https://github.com/bjeanes/heddle/commit/0a802035a4ebfeb03f825152cb1b96ae3349bb4b
[`35e571e`]: https://github.com/bjeanes/heddle/commit/35e571e0dbcd984ffc1aa703659e9da74e1511ed
[`68ceecd`]: https://github.com/bjeanes/heddle/commit/68ceecd44832c76be0b37514f5f851487d485759
[`1acf47e`]: https://github.com/bjeanes/heddle/commit/1acf47eb9b550fe2c221ae22ff88f512a1da7b75
[Position encoding]: https://github.com/bjeanes/heddle/blob/0a802035a4ebfeb03f825152cb1b96ae3349bb4b/DESIGN.md#position-encoding
[Startup and capabilities]: https://github.com/bjeanes/heddle/blob/0a802035a4ebfeb03f825152cb1b96ae3349bb4b/DESIGN.md#startup-and-capabilities
