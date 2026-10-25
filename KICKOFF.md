# monorepo-lsp: kick-off

## Background

Picture a monorepo holding several services, each developed and run in its own
Docker Compose service. Some are written in different languages, such as a Ruby
API next to a TypeScript frontend. Others share a language but little else. Two
Ruby services might be on different Ruby versions, with separate bundles and a
different set of gems in each.

An editor working in a repo like this needs a different language server for
each language. It also needs a separate _instance_ of the same server for each
service that shares a language. Each instance has to be rooted at its own
service and see that service's dependencies.

That last requirement is where things fall apart. A language server has to run
where the project's dependencies are installed. In this kind of setup they are
often installed only inside each service's container. When an editor starts the
server on the host, the server can't load the project's libraries. Sometimes it
can't start at all.

VS Code's usual answer is Dev Containers, which runs the editor's extensions
inside a container. However, a window attaches to only one container at a time.
Working across the monorepo therefore means opening a window per service.

I recently wrote a harness in my Neovim config[1] that solves this for Neovim.
The project declares, in a config file, which Compose service each part of the
repo runs its language server in. Neovim starts the server there with
`docker exec`. It rewrites every path in the messages passing between editor and
server, using a mapping it works out from the container's mounts. Some files
exist only inside the container, such as installed libraries. The harness
copies those out to a temporary directory when they're opened, which keeps
go-to-definition working inside dependencies.

That harness only helps people using Neovim. Most people on a team are likely
to be using something else.

## What this is

`monorepo-lsp` takes that harness out of the editor and makes it a standalone
command. To the editor it is one ordinary language server.

Behind that, the proxy reads a config file checked into the repo. For each file,
the config says which kinds of server handle it, such as a language server and
a linter. It also says how each of those is started for that part of the
repo. That might be on the host, inside a Compose service, or through any other
command that can run a server elsewhere. The proxy starts each backend on first
use and forwards the file's messages to it, translating paths in both
directions.

An editor can run the proxy for all of the repo's servers at once, by running
`monorepo-lsp` on its own. It can also name the servers it wants, for example
`monorepo-lsp ruby-lsp`. That suits an editor extension built around one
particular server.

Since the routing and the container handling live in the proxy, any editor that
can launch a language server gets them. A team describes its setup once, in the
repo.

The same is true of coding agents. Bridges such as mcp-language-server[2]
give an agent a language server's go-to-definition, references, diagnostics and
rename over MCP, which is more reliable than searching the text. However, each
bridge starts one server command for one workspace. In a monorepo, that server
can't see the dependencies of most services. Its answers are then wrong or
missing. The agent has no way to tell those apart from good ones. With
`monorepo-lsp` as the bridge's server command, an agent can trust what the
language server tells it anywhere in the repo.

## Goals

- Any editor that can start a language server over stdio can use it. We'll test
  against Neovim and VS Code first.
- A coding agent can use it through an MCP bridge to a language server, with
  the same per-service accuracy an editor gets.
- One proxy can serve every language in the repo, or just the servers it's
  asked for.
- Different parts of a repo can start the same server differently, whether that
  means a different container, a different command, or the host.
- Docker is supported without being assumed. A backend can run on the host,
  through any command that runs a server elsewhere, or in a Docker container or
  Compose service.
- Files that exist only where the server runs, such as libraries installed in a
  container, can be opened from the editor.
- Everyone on a team gets the same setup from the config in the repo.

## Not in scope, for now

- Replacing what language extensions do outside the protocol. For example,
  Shopify's Ruby LSP extension for VS Code runs tests, the debugger and its
  dependency view on the host. Its maintainers have said they aren't planning
  container support[3]. Anyone using the proxy goes without features like
  those.
- Windows hosts and remote Docker contexts.
- Clever merging of answers when several servers handle the same file. The
  first version uses simple rules, described in `DESIGN.md`.

## Prior art

Nothing I found does all of this. The closest is lspdock[4], a Rust proxy that
runs one server in one named container and maps paths between the two. It
doesn't route by directory or know about Compose. It also expects the container
to be running already. Rewriting URIs inside responses and running several
servers are on its roadmap.

Pod[5] does something similar for Podman. Emacs's lsp-mode has lsp-docker[6],
which maps paths using mappings you configure, though only for Emacs.
dockered-language-servers[7] avoids path translation altogether. It mounts your
home directory into the container at the same path, at the cost of exposing all
of it. Garnix wrote up nearly this design, a config file that maps paths to
server commands, but only built a prototype with hard-coded parameters[8].

Several LSP multiplexers put more than one server behind a single connection.
None of them route by path or know about containers. lsp-mux[9] routes by file
type. The Emacs lsp-mux[10] routes by request method. multi-lsp-proxy[11] runs
several servers per language without any routing that I could see. lspmux[12]
solves the opposite problem, sharing one server between several editors.

Getting lspdock to where the Neovim harness already is would mean rebuilding
most of the harness inside it. So, we'll build on the harness, which already
works on a real monorepo, and borrow from lspdock where it helps.

## Design

How the proxy works, from its config model to how it routes messages, is in
`DESIGN.md`.

## Implementation language

The implementation language is undecided. The proxy should ship as a single
static binary for macOS (arm64 and x86_64) and Linux, which means installing it
is a matter of downloading one file. It has to handle several streams at once
reliably and rewrite arbitrary JSON. Speed matters little.

I'd like to try Jolt[13], a Clojure that runs on Chez Scheme and builds
self-contained executables. It is young (the repo was created in June 2026).
However, it ships babashka.process, fibers and `core.async`, which should cover
the stream handling. Before we commit to it, a spike of a day or so should show
that it can:

1. run `docker compose exec --interactive` and pump stdin and stdout
   concurrently without stalling;
2. round-trip several MB of JSON through a generic tree rewrite at a reasonable
   speed. Since Jolt's standard library has no JSON support, that includes
   finding a library;
3. build binaries that run on other people's machines. Jolt's prebuilt releases
   cover only macOS arm64 and Linux x86_64.

If any of those is painful, Rust is the fallback. Clojure with GraalVM
native-image is the alternative if we want to stay in Clojure. clojure-lsp ships
that way.

## Milestones

1. The language spike, as above.
2. One server in one container. `monorepo-lsp ruby-lsp` serves a single scope
   through Compose, with path translation and container-only files. We'll test
   it from Neovim against a real Ruby service.
3. The full config model. That covers several scopes and server types, running
   with no names given, and every runner. It also covers several servers on one
   file, with their diagnostics merged. We'll test it behind mcp-language-server
   as well, since an agent working across the repo relies on exactly this
   routing.
4. The VS Code extension, plus setup docs and an example repo.
5. Later on, smarter merging of answers from several servers, and formatters
   offered through `textDocument/formatting`.

## Open questions

- The name. `monorepo-lsp` is a working name.
- The config format and file name. The design uses TOML for now, which will be
  cheap to change once we've tried writing real configs in it. EDN is out,
  since it is rarely used outside Clojure.
- Whether formatters belong in the proxy. The Neovim harness also runs
  formatters such as Prettier inside a container.
- How people install it, such as a Homebrew tap, Nix, release binaries, or
  bundled into the VS Code extension.
- The narrower questions listed at the end of `DESIGN.md`.

[1]: `nix/packages/nvim/plugins/container_lsp.lua` in my dotfiles
[2]: https://github.com/isaacphi/mcp-language-server
[3]: https://github.com/Shopify/ruby-lsp/discussions/3738
[4]: https://github.com/richardhapb/lspdock
[5]: https://willhbr.net/2026/03/13/language-servers-in-containers/
[6]: https://github.com/emacs-lsp/lsp-docker
[7]: https://github.com/aidalgol/dockered-language-servers
[8]: https://garnix.io/blog/taking-lsp-one-step-further/
[9]: https://github.com/lsp-mux/lsp-mux
[10]: https://github.com/LuciusChen/lsp-mux
[11]: https://github.com/messense/multi-lsp-proxy
[12]: https://codeberg.org/p2502/lspmux
[13]: https://jolt-lang.net/
