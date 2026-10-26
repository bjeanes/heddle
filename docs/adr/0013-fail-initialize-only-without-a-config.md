# 0013. Fail `initialize` only when there's no config

Status: Accepted, 2026-10-26. Reverses part of [`a2e1329`], which answered
`initialize` even when there was no config.

## Context

heddle reads its config when the client sends `initialize`. The config might be
missing, invalid, or start no server types. At first, heddle failed `initialize`
in all of these cases. Most clients treat that as the server failing to start.
They may retry it or stop talking to it, with the reason buried in a log.

However, some clients start heddle where there's no config at all. A Claude Code
plugin installed for a user starts heddle in every repo it opens. Claude Code
gives each file extension to the first plugin that claims it. If heddle answers
in a repo with no config, it takes those files from other servers and serves
nothing. Claude Code never shows heddle's message either.

## Options considered

- **Always fail `initialize`.**
- **Always answer**, then show a message saying what's wrong and serve nothing
  ([`a2e1329`]).
- **Fail only when there's no config at all.**

## Decision

Without a config, heddle fails `initialize` with `RequestFailed` and
`retry: false`, since asking again would find no config again ([`1d0fb78`]).
`InternalError` would have suggested a bug in heddle. An invalid config, or one
that starts no server types, still gets an answer and a message, since whoever
wrote it wants to use heddle and needs to know what to fix. Someone who runs
`heddle` by hand at a terminal gets the reason on stderr straight away, and
heddle exits 1 ([`9fb8b64`]). See [Failures].

## Consequences

The Neovim module only starts heddle where a config exists, so it sees no
change. A client that starts heddle everywhere can tell that heddle won't serve
that repo. However, Claude Code claims the file extensions for the plugin
whether or not heddle starts. So, the plugin still has to be enabled only where
there's a config.

[`a2e1329`]: https://github.com/bjeanes/heddle/commit/a2e1329f56ef5b37822c2f85266f5d7b6ec7bb66
[`1d0fb78`]: https://github.com/bjeanes/heddle/commit/1d0fb78b1dbc5acfde4d7417635171b10213866d
[`9fb8b64`]: https://github.com/bjeanes/heddle/commit/9fb8b645c4e64e3885ab6840e3b4109c7b14da76
[Failures]: https://github.com/bjeanes/heddle/blob/1d0fb78b1dbc5acfde4d7417635171b10213866d/DESIGN.md#failures
