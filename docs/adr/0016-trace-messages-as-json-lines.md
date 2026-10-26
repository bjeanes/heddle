# 0016. Trace messages as JSON Lines, outside the proxy

Status: Accepted, 2026-10-26

## Context

Clients such as Claude Code run heddle as a plugin and give no view of the
traffic. To debug them, we need to see exactly what the client sends, and what
heddle passes on to each backend.

## Options considered

- **Switch tracing on from the command line**, which for a plugin means changing
  the plugin.
- **An environment variable**, for clients whose config makes arguments awkward.
- **A table in `.heddle.toml`**, which I asked for so that one project can be
  traced without touching the client.

## Decision

`--trace FILE`, `HEDDLE_TRACE` and a `[trace]` table each append every message
to a file as JSON Lines, which `jq` can read. Each entry has its time, who it
came from or went to, and the backend's server type and scope ([`db44b5b`],
[`01e3079`]). The proxy knows nothing of tracing. `serve!` traces what it reads
from stdin, and wraps the functions that send to the client and connect to
backends. Each write opens the file to append, which is slower than holding it
open, but loses nothing if heddle is killed. If a write fails, heddle logs it
once and stops tracing, since a bad path should never stop it serving. See
[Tracing messages].

## Consequences

Since the config is only read at `initialize`, a config's tracer is switched on
then, before `initialize` is traced. With tracing off, the cost is reading an
atom per message and reading the config twice.

[`db44b5b`]: https://github.com/bjeanes/heddle/commit/db44b5ba09229fbb74b3e14bf5cd7572180b1438
[`01e3079`]: https://github.com/bjeanes/heddle/commit/01e3079d078f7c59f934971e6d0d1623b271808b
[Tracing messages]: https://github.com/bjeanes/heddle/blob/01e3079d078f7c59f934971e6d0d1623b271808b/README.md#tracing-messages
