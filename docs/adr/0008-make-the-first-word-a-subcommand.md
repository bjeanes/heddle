# 0008. Make the first word a subcommand, defaulting to `serve`

Status: Accepted, 2026-10-26

## Context

Every bare word on the command line was a server type's name, as in
`heddle ruby-lsp`. So, asking which languages the config covers had to be a
flag, `--languages`, which a server type's name can't start with. That left no
room for other commands. It also broke the Neovim module. The module appended
`--languages ROOT` to whatever `cmd` it was given, which failed once `cmd` named
a server type.

## Options considered

- **Keep flags** for everything that isn't serving.
- **A `start` subcommand**, which I suggested. It reads like a daemon you'd
  later stop.
- **A `serve` subcommand that the bare command also means.** Since editors and
  bridges run the bare command, every existing snippet would keep working.
- **For an unknown command**, suggest the `serve` form, or only say where server
  types go. The word could as easily be a misspelt command.

## Decision

The first bare word is a command. `heddle` alone, or with only `--eager`, means
`serve`, and server types can only be named after `serve` ([`42d5a03`]). An
unknown command is an error that says where server types go, without guessing.
`languages` replaced `--languages` outright, since nothing had been released.
`check`, `init` and `help` followed ([`20e0c86`]). See [Invocation].

## Consequences

The Neovim module runs `languages` with the binary from `cmd`, which fixed it.
Only `heddle ruby-lsp` changed, to `heddle serve ruby-lsp`.

[`42d5a03`]: https://github.com/bjeanes/heddle/commit/42d5a036217ee85a3457723d9714b4c9260a8f1c
[`20e0c86`]: https://github.com/bjeanes/heddle/commit/20e0c86065e8ae67b32800917bad5b0c307174dc
[Invocation]: https://github.com/bjeanes/heddle/blob/42d5a036217ee85a3457723d9714b4c9260a8f1c/DESIGN.md#invocation
