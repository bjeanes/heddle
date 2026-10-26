# 0019. Simplify the config, and expand it when it loads

Status: Accepted, 2026-10-27

## Context

Writing a config for a real monorepo showed where the format strains. Every
scope repeated its `runner` and `paths`. A scope can't remove a key it inherits.
A service switched to the host therefore still inherited its container's
`paths`. Writing `paths = {}` to clear them passed `check`. However, clj-toml
read the empty table as nil, which left the scope with no `paths` at all. Code
shared between services is served by none of their backends
([0003](0003-separate-server-types-from-scopes.md)). The built-in runners are
privileged by name, and the config can only live at the repo root.

## Options considered

- **Unsetting a key.** TOML has no nil. `runner = ""` reads like a typo and
  doesn't extend to `paths`. `false` fits every key.
- **`runner = "host"`** for the host, which privileges that name.
- **Keeping built-in runners**, which pick up fixes, or dropping them, which
  keeps every runner unprivileged and insulated from upstream breakage.
- **Shared code** declared with `also = [...]` on the scope that uses it, which
  mirrors how a service in a monorepo usually loads internal dependencies, by a
  relative path to them. The alternative was `use = [...]` on the shared
  directory, a new kind of entry needing special cases throughout.
- **`include`, or several config files**, to share runners. That needs merge
  rules, relative paths and cycle checks, while `--config` covers a personal
  config.
- **YAML**, so that people can de-duplicate with anchors and the format stays
  simple.

## Decision

The changes landed in this order.

1. The config is expanded when it's parsed, so that each scope entry holds
   everything it needs. Where each value came from is kept in metadata on the
   entry, since strings can't hold metadata ([`7fc3c18`]).
2. `false` unsets any key, and `check` rejects `paths = {}` ([`456bee6`]).
   heddle uses a fork of clj-toml that reads `{}` as an empty table
   ([`bbd3e0f`]).
3. `runner` becomes optional, with no reserved name ([`743425b`]).
4. A server type that no scope names runs across the whole repo ([`bcc008a`]).
5. A runner's `defaults` can set `paths` ([`43de841`]).
6. Every built-in runner goes. The README and `heddle init` give examples
   ([`18d0679`], [`60e4a3e`]).
7. `--config` and `HEDDLE_CONFIG` choose the file. The repo root stays the
   client's workspace root ([`7242bff`], [`6f0484b`]). Since several repos can
   now share a config, each repo gets its own directory under `cache.path`
   ([`392fcd2`]).
8. Shared code is served through `also`, with diagnostics de-duplicated across
   backends and `check` making sure `paths` cover those directories
   ([`d60528e`], [`0e25c5b`]).
9. `heddle config` prints the expanded config, with origins as comments. It
   comes last because it needs a TOML writer ([`d7fc1d5`], [`42c59a8`]). The
   writer's tests needed a clj-toml fix for `\uXXXX` escapes ([`2452a96`]).

`include` is out for now. YAML anchors are the fallback if the format keeps
growing.

## Consequences

Each rule lives in one function. It isn't clear that this makes the code any
smaller. Adding a scope for a server type removes its repo-wide default, which
`heddle config` makes visible. This supersedes the built-in runners from
[0004](0004-define-runners-as-command-templates.md).

[`7fc3c18`]: https://github.com/bjeanes/heddle/commit/7fc3c186677dfb942f28f296b3dc61e6ad446787
[`456bee6`]: https://github.com/bjeanes/heddle/commit/456bee61251f3d4108d92c4437c7d44ca5702c7f
[`bbd3e0f`]: https://github.com/bjeanes/heddle/commit/bbd3e0f880385ba3858281b042037a3c180a5994
[`743425b`]: https://github.com/bjeanes/heddle/commit/743425be1fac61840de50cdda911e16c987e40af
[`bcc008a`]: https://github.com/bjeanes/heddle/commit/bcc008a303da89a6027d109f115497023fb8dab7
[`43de841`]: https://github.com/bjeanes/heddle/commit/43de841eb393ce712cecbf7f0b75af65c161524d
[`18d0679`]: https://github.com/bjeanes/heddle/commit/18d0679f5d05f67ed95ae31803c73e7559189b62
[`60e4a3e`]: https://github.com/bjeanes/heddle/commit/60e4a3e43d1bb59d23629bc459a3657f1b6f5b92
[`7242bff`]: https://github.com/bjeanes/heddle/commit/7242bffb1505012c0cae434470ac230f68e13e2c
[`6f0484b`]: https://github.com/bjeanes/heddle/commit/6f0484bd5c7d3ed6764eea190b30bc8106741a44
[`392fcd2`]: https://github.com/bjeanes/heddle/commit/392fcd2204078886b3d552b72422d9ee2057dd2d
[`d60528e`]: https://github.com/bjeanes/heddle/commit/d60528e27db4883e8b46b55412e7b53bc93f7dc7
[`0e25c5b`]: https://github.com/bjeanes/heddle/commit/0e25c5b290747941ff5b79ab02f60be54359b461
[`d7fc1d5`]: https://github.com/bjeanes/heddle/commit/d7fc1d59eeb914781ab3b3af71e1ce1d2c0eb3f1
[`42c59a8`]: https://github.com/bjeanes/heddle/commit/42c59a8d72b122e197a1ce1df2e579fe5e24d64f
[`2452a96`]: https://github.com/bjeanes/heddle/commit/2452a9623b4a90142f0cca31157c28fd851395be
