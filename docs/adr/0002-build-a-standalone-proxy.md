# 0002. Build a standalone proxy

Status: Accepted, 2026-10-25

## Context

In a monorepo of services that each run in their own container, an editor needs
a language server per language and a separate instance per service. Each
instance has to run where that service's dependencies are installed, which is
often only inside its container. I had written a harness for Neovim that does
this, but only people using Neovim could benefit from it.

## Options considered

- **Dev Containers**, VS Code's usual answer. A window attaches to one
  container, so working across the repo means a window per service.
- **lspdock**, the closest prior art. It runs one server in one named container.
  It doesn't route by directory, doesn't know about Compose, and expects the
  container to be running already. Getting it to where the harness was would
  mean rebuilding most of the harness inside it.
- **Existing multiplexers**, such as lsp-mux. They route by file type or by
  request method. None of them route by path or know about containers.
- **Pointing each editor's language extension at a wrapper.** Even where an
  extension lets you change its server command, it uses one command for the
  whole window. It doesn't let you vary that command by the file's path. A small
  wrapper could choose the container itself. However, someone would have to
  write one for every language, and set it up again in every editor.
- **A standalone proxy** that looks like one ordinary language server to the
  client, configured by a file checked into the repo.

## Decision

Build the proxy, starting from the Neovim harness and borrowing from lspdock
where it helps. What language extensions do outside the protocol, such as a test
explorer or debugger, is out of scope. See [KICKOFF.md], under "Prior art" and
"Not in scope, for now".

## Consequences

Any client that can start a server over stdio can use it, including agents
through an MCP bridge or a plugin. A team describes its setup once, in the repo.
However, someone using an extension like Shopify's has to switch it off and use
a generic client instead. VS Code has no generic client of its own. So, it still
needs a small companion extension, which doesn't exist yet ([KNOWN-ISSUES.md]).

[KICKOFF.md]: https://github.com/bjeanes/heddle/blob/2d0f45dd351e3abc2e60a820157ae0d7f2afbc74/KICKOFF.md
[KNOWN-ISSUES.md]: https://github.com/bjeanes/heddle/blob/7e732ced2f21be2115e75cc60a8e41f629890e63/KNOWN-ISSUES.md#editors-and-agents
