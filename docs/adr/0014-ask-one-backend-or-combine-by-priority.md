# 0014. Ask one backend or combine them, by priority

Status: Accepted, 2026-10-25, revised 2026-10-26

## Context

Several servers can handle one file, such as ruby-lsp and RuboCop on a Ruby
file. Some requests want one answer, such as hover or definition. Others, such
as completion and code actions, can take something from each. Clever merging was
out of scope for the first version ([KICKOFF.md]).

## Options considered

- **Config order** to choose who answers. TOML doesn't promise to keep a table's
  keys in order, and the parser heddle uses loses it once a table has more than
  a few keys ([`a5b352e`]).
- **Sorted server type names**, which made the outcome depend on naming.
- **An explicit `priority`** on each server type.
- **While a backend starts**, a combined request could wait for every backend,
  wait up to some limit, or go only to those that are ready.

## Decision

A request that wants one answer goes to the backend whose server type has the
highest `priority`, among those that offer the feature. `priority` is set on
each server type, and ties go by server type name ([`06d4993`]). Completion,
code actions, code lenses, references, document links, inlay hints, pulled
diagnostics and workspace symbols are concatenated in priority order
([`6c21937`], [`4bffed1`], [`68ceecd`]). When several backends offer code
actions, each title names its server type, since the spec gives an action no ID
([`7b5de41`]). Pushed diagnostics are published as a union per file. Duplicate
references and workspace symbols, which appear when several backends index the
same shared directory, are dropped ([`86a3ca2`]). A combined request goes
straight to the backends that are ready, and waits only if none is
([`7cddac2`]). I chose that over a maximum wait to keep it simple. See [Requests
about one file].

## Consequences

A combined answer leaves out a backend while it starts. Semantic token legends
aren't merged. Code lenses, links and inlay hints aren't de-duplicated, since
two servers may give different ones on the same range.

[`a5b352e`]: https://github.com/bjeanes/heddle/commit/a5b352e15522abcab0d1d99857bdb682fc132822
[`06d4993`]: https://github.com/bjeanes/heddle/commit/06d49934b93490b0fa712055c5d4d308fce71e7e
[`6c21937`]: https://github.com/bjeanes/heddle/commit/6c21937f8f18c46789c1521becb72aa0ea9f1389
[`4bffed1`]: https://github.com/bjeanes/heddle/commit/4bffed143ca006e47a41e4716add80d5beb97181
[`68ceecd`]: https://github.com/bjeanes/heddle/commit/68ceecd44832c76be0b37514f5f851487d485759
[`7b5de41`]: https://github.com/bjeanes/heddle/commit/7b5de41cb596c754edc2e420e519b64527ff5125
[`86a3ca2`]: https://github.com/bjeanes/heddle/commit/86a3ca26718b8afcd32200a0b21157bca79219ea
[`7cddac2`]: https://github.com/bjeanes/heddle/commit/7cddac2106943f607ecd9f622fea4acbc0fff16a
[KICKOFF.md]: https://github.com/bjeanes/heddle/blob/86a3ca26718b8afcd32200a0b21157bca79219ea/KICKOFF.md
[Requests about one file]: https://github.com/bjeanes/heddle/blob/86a3ca26718b8afcd32200a0b21157bca79219ea/DESIGN.md#requests-about-one-file
