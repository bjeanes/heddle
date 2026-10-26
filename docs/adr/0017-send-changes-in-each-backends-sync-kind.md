# 0017. Send each backend changes in the form its sync kind asks for

Status: Accepted, 2026-10-26

## Context

heddle asks every client for a document's full text on every change
([0005](0005-answer-initialize-and-register-features-later.md)), and passed each
`didChange` on unchanged. I had read the spec as allowing a full-text change
whatever kind of sync a backend asked for. ruby-lsp asks for incremental sync.
Given a change with no range, it raised an error and kept the old text. Every
answer after the first edit came from that stale text. A trace from using heddle
in a real monorepo showed it.

## Options considered

- **Pass changes on as they come**, which breaks ruby-lsp.
- **Ask the client for incremental changes**, pinned in DESIGN.md in case full
  text turns out to be too slow. heddle would still need to keep each file's
  text for backends that start late.
- **Convert each change** into the form each backend's `textDocumentSync` asks
  for.

## Decision

heddle converts ([`03c5851`]). A backend that syncs in full gets the new text.
One that syncs incrementally gets a single edit replacing the whole of the text
before the change. The edit's end counts UTF-16 code units, which means counting
a character above U+FFFF as two in Jolt's strings. A backend that asks for no
sync, or doesn't say, gets no `didChange`, as the spec says. VS Code and Neovim
treat such a server the same way. If a client sends ranged changes anyway,
heddle applies them to the text it keeps. See [Open files].

## Consequences

Messages are larger than with real incremental sync. A lax server that leaves
out `textDocumentSync` but expects changes gets none, though it would already be
broken in VS Code and Neovim.

[`03c5851`]: https://github.com/bjeanes/heddle/commit/03c5851873f339ec7a7c800c52a5d1d825e1d73d
[Open files]: https://github.com/bjeanes/heddle/blob/03c5851873f339ec7a7c800c52a5d1d825e1d73d/DESIGN.md#open-files
