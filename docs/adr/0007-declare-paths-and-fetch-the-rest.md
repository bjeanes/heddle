# 0007. Declare paths, and fetch everything else

Status: Accepted, 2026-10-25

## Context

A server in a container sees files at different paths from the client. Some
files it refers to, such as installed gems, only exist inside the container. The
Neovim harness worked its mappings out from `docker inspect`. It copied a
container-only file to the host when Neovim opened its buffer.

## Options considered

- **Work mappings out from `docker inspect`.** That spares writing them down,
  but ties heddle to Docker.
- **Declare mappings in the config**, and treat any path they don't cover as a
  file to copy out with the runner's `read`. Copies from a named volume would
  then be read-only. I was fine with that, since the files anyone edits are
  likely to be bind-mounted.
- **Copy a file when the client opens it.** Most clients, including VS Code and
  MCP bridges, give heddle no hook to do that.
- **Copy a file before passing on the message that refers to it**, as lspdock
  does.

## Decision

`paths` maps repo-relative directories to server paths, with the longest match
winning. A scope without `paths` behaves as though the whole filesystem mapped
onto itself, inside the repo and out, as it does for a server on the host.
That's wider than `{ "." = "/path/to/repo" }`, which only covers the repo. Once
a scope has `paths`, a server path they don't cover has no known location on the
client's side. So, heddle pulls a read-only copy of it from the server's side
with the runner's `read`, and caches it, before it forwards the message
([`883748f`]).

heddle walks every string and key in every message, rewriting `file:` URIs
wherever they appear, even inside hover markdown ([`fb10e86`]). A bare string is
only rewritten if `paths` covers it. A lone `/` never is. Servers list
characters such as `.` and `/` as completion triggers, so that the editor asks
for completions as soon as one is typed, and rewriting them would break that. At
first heddle only fetched files named in answers. Later, it fetched them for
every message from a backend ([`2e45031`]). See [Paths] under Runners, and under
Routing messages.

## Consequences

Every runner has the same shape. Fetched files can't be edited. A slow `read`
holds up that backend's later messages.

[`883748f`]: https://github.com/bjeanes/heddle/commit/883748f93c13ffaf60c7590f80c12b8c6495e637
[`fb10e86`]: https://github.com/bjeanes/heddle/commit/fb10e864534d316193d603af8e2472f1e6950dae
[`2e45031`]: https://github.com/bjeanes/heddle/commit/2e450317568e026d9b17454897d9afdcc11e4e21
[Paths]: https://github.com/bjeanes/heddle/blob/2e450317568e026d9b17454897d9afdcc11e4e21/DESIGN.md#paths
