# 0006. Start backends lazily, with `--eager` as a flag

Status: Accepted, 2026-10-26

## Context

A backend can be a language server in a container. A repo may have many of them.
By default, heddle starts each one on the first `didOpen` of one of its files.
That means answering `initialize` before any backend's capabilities are known.
Some clients only use what a server announced at startup. Neovim only runs the
commands listed in the `initialize` answer. An agent's MCP bridge finds no
workspace symbols, or references in other services, until a file is opened.
Semantic tokens and on-type formatting can't be announced at all.

## Options considered

- **Lazy start only.** When we first agreed on lazy registration, I pinned an
  eager mode as a hypothetical for later, noting that it felt "somewhat
  fallible".
- **Eager start**, as a config setting or as a command-line flag.
- **Choosing which backends start eagerly**, per server type or scope.

## Decision

Backends start lazily by default. `--eager` starts every backend when the client
sends `initialize`, waits up to 30 s for them, and announces their combined
capabilities in the answer ([`a2ce352`]). It's a flag, since whether it suits
depends on the client more than the repo. An agent wants the whole repo at once.
An editor may not want every container's server started whenever it opens one
file. See [Startup and capabilities] and [Starting servers eagerly].

## Consequences

A backend that starts after the answer can't widen it, with new commands,
trigger characters or a token legend. Restarts stay lazy. The Claude Code plugin
runs `--eager`, and its first request after startup can hit "server is
starting". I haven't decided whether that trade-off is right. Choosing which
backends start eagerly is pinned in [DESIGN.md].

[`a2ce352`]: https://github.com/bjeanes/heddle/commit/a2ce3527d680392103e67f0dd11e7cbac94c2141
[Startup and capabilities]: https://github.com/bjeanes/heddle/blob/a2ce3527d680392103e67f0dd11e7cbac94c2141/DESIGN.md#startup-and-capabilities
[Starting servers eagerly]: https://github.com/bjeanes/heddle/blob/a2ce3527d680392103e67f0dd11e7cbac94c2141/README.md#starting-servers-eagerly
[DESIGN.md]: https://github.com/bjeanes/heddle/blob/a2ce3527d680392103e67f0dd11e7cbac94c2141/DESIGN.md#pinned-for-later
