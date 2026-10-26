# 0010. Name the project heddle

Status: Accepted, 2026-10-26

## Context

"monorepo-lsp" was only ever a working name. It undersells the project, which is
just as useful in a single polyglot app as in a monorepo. A project's config
could also come to cover more than language servers, such as linters.

## Options considered

- **Names that riff on weaving language servers together**, without leaning too
  heavily on "LSP".
- **weft**, which at least four or five projects already use for AI and agent
  tooling.
- **heald**, an unused synonym for heddle. Its `d` suffix suggests a daemon,
  which could confuse.
- **heddle**, which a couple of projects already use.

## Decision

heddle ([`668a2f0`]). A heddle is the part of a loom that lifts selected warp
threads so the weft can pass between them, much as heddle picks out the servers
that match each file. heald stays in reserve if the name becomes a problem.

## Consequences

The rename touched the binary, the namespaces, the config file (`.heddle.toml`),
the cache directory, the backend ID prefix and the Neovim module. Fetched copies
in the old cache directory were left behind.

[`668a2f0`]: https://github.com/bjeanes/heddle/commit/668a2f04b173987f924da44bf104ff2cd08b6f9f
