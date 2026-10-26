# 0018. Cache fetched files by slot, outside the repo

Status: Accepted, 2026-10-26

## Context

At first, each backend kept its fetched files under its own ID in the temp
directory ([`883748f`]). Since the ID is random, every restart started a new
directory, reused nothing and left the old one behind.

## Options considered

- **Key by backend ID**, as it was.
- **Key by repo, server type and scope.** Two backends can have different files
  at the same server path, which rules out keying by repo alone.
- **Where the root is.** The temp directory changes with every `nix develop`
  shell. `XDG_CACHE_HOME` isn't set on macOS. A directory inside the repo would
  be easy to find.
- **How to name a scope's directory.** Percent-encoding `services/api` into one
  segment, or nesting its directories.

## Decision

The cache is keyed by repo (its name and a CRC-32 of its path), server type and
scope ([`19f33b9`]). Each backend refreshes a file the first time it needs it,
writing a new copy and renaming it over the old ([`327e44d`]). If that fails,
the old copy stays, since a stale file is more use than a location that leads
nowhere. A `[cache]` table chooses the root with `env`, `path` or `xdg = true`
([`6420844`], [`6fd63dc`]). It's a table, since in a string heddle couldn't tell
a variable from a relative path without a `$` syntax that looks like shell but
isn't. A `path` is used as given ([`c1b3185`]). Since a percent-encoded scope
showed up as `%252F` in clients' URIs, the scope's directories are nested
instead, followed by a `=` segment ([`e724da8`]). Without the marker,
`services/api`'s copy of `/x.rb` and `services`' copy of `/api/x.rb` would land
on the same path. Scope directories named `=` are escaped, since forbidding `=`
in scopes would have turned away configs that work. See [Paths] and [The cache].

## Consequences

The cache belongs outside the repo. When I tried it in a git-ignored directory
inside one, Claude Code dropped every definition pointing there ([`82728e2`]).
Claude Code then needs settings to read the cache ([`c58fdc7`]). Nothing cleans
the cache up. Copies in older layouts are left behind. Checkouts that share an
absolute `path` can replace each other's copies.

[`883748f`]: https://github.com/bjeanes/heddle/commit/883748f93c13ffaf60c7590f80c12b8c6495e637
[`19f33b9`]: https://github.com/bjeanes/heddle/commit/19f33b9dcb277cc03c92ea46e7f3c1f9654d23af
[`327e44d`]: https://github.com/bjeanes/heddle/commit/327e44d9f8d6a4052b77b82b141059bf2f04824f
[`6420844`]: https://github.com/bjeanes/heddle/commit/6420844229eca950c313fd5f5f5ea4ba700d588f
[`6fd63dc`]: https://github.com/bjeanes/heddle/commit/6fd63dc453b91c40fc295fade9b45f80151e9451
[`c1b3185`]: https://github.com/bjeanes/heddle/commit/c1b3185680afe57f293cdd8d8d37d236b0b90af7
[`e724da8`]: https://github.com/bjeanes/heddle/commit/e724da87723cfcd7f938ec109400178c24418a90
[`82728e2`]: https://github.com/bjeanes/heddle/commit/82728e2d3e92fc41bc20b5465ebd2645a76968a5
[`c58fdc7`]: https://github.com/bjeanes/heddle/commit/c58fdc7bee0d7e44411908772ce41a4ed2e97ae0
[Paths]: https://github.com/bjeanes/heddle/blob/e724da87723cfcd7f938ec109400178c24418a90/DESIGN.md#paths-1
[The cache]: https://github.com/bjeanes/heddle/blob/e724da87723cfcd7f938ec109400178c24418a90/README.md#the-cache
