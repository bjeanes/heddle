# monorepo-lsp

`monorepo-lsp` is a language server proxy for monorepos whose services each
run in their own container. To your editor, or to an agent's MCP bridge, it
is one ordinary language server. Behind that, it reads a config checked into
the repo and starts the right language servers for each part of the repo,
wherever they need to run, such as inside each service's Compose container.
It translates paths in both directions. When a server points at a file that
only exists in its container, such as an installed gem, the proxy copies the
file out so you can open it.

[KICKOFF.md](KICKOFF.md) covers why it exists and
[DESIGN.md](DESIGN.md) covers how it works.
[docs/adr](docs/adr/README.md) records the decisions behind it, and what else
was considered.

## Status

This is an early prototype. It has been tried with ruby-lsp and RuboCop
running in Docker Compose on macOS, from Neovim 0.12 and from the session
driver in `dev/`. Expect rough edges.

## Building it

You need Nix with flakes. The dev shell provides Jolt, for aarch64 macOS and
x86_64 Linux.

```sh
nix develop
jolt build -m monorepo-lsp.main -o out/monorepo-lsp
```

That writes a single binary to `out/monorepo-lsp`. Put it on your `PATH`,
or point your editor at it directly.

## Configuring a repo

The proxy looks for `.monorepo-lsp.toml` at the root of the workspace your
editor opens. A minimal one, for one Ruby service, looks like this:

```toml
# Server types say which servers handle which files, and how to start them
# by default.
[servers.ruby-lsp]
languages = ["ruby"]
boot = { command = ["bundle", "exec", "ruby-lsp"] }

# Scopes say how to start each server type for the files under a directory.
# The longest scope that contains a file wins.
[scopes."services/api"]
# Run it with `docker compose exec` in the `api` service, which mounts
# services/api at /app.
ruby-lsp = { runner = "compose", service = "api", paths = { "services/api" = "/app" } }
```

Since the proxy doesn't start containers itself, bring them up first with
`docker compose up --detach`.

To check which languages a config covers, run `monorepo-lsp --languages` from
the repo root, or give it the root as an argument. It prints them as JSON,
such as `{"languages":["ruby"]}`, or the config's problems if there are any.

## Starting servers eagerly

By default, the proxy starts each server the first time you open one of its
files. That keeps an editor from starting every container's server when you
only open one file. However, until then, the proxy can't tell the client
about that server's features, and workspace symbol search doesn't cover it.

`monorepo-lsp --eager` (or `monorepo-lsp --eager ruby-lsp`, naming server
types as usual) starts every server in the config as soon as the client
connects, and waits up to 30 s for them before finishing its handshake. The
client then learns every feature and command up front. Use it for agents, and
for clients that only use what a server announces at startup. A server that
takes longer joins when it's ready, as it would without the flag. A server
that fails to start is reported as usual, and starts again the next time you
open one of its files.

For more, see the examples:

- [`examples/ruby-compose`](examples/ruby-compose) is a single Ruby service.
- [`examples/monorepo`](examples/monorepo) has two Ruby services with their
  own bundles, plus RuboCop in a separate `lints` service that checks the
  whole repo.

## Neovim

This needs Neovim 0.11 or newer, though I've only tried it with 0.12.6. The
repo is also a Neovim plugin. Install it with your plugin manager, such as
lazy.nvim, pointed at a checkout:

```lua
{
  dir = '/path/to/monorepo-lsp',
  config = function()
    require('monorepo_lsp').setup()
  end,
}
```

Or add a checkout to your runtimepath yourself:

```lua
vim.opt.runtimepath:append('/path/to/monorepo-lsp')
require('monorepo_lsp').setup()
```

`setup()` configures and enables a `monorepo_lsp` client, which only starts in
a repo with a `.monorepo-lsp.toml`. It takes these options:

- `cmd` runs the proxy, and defaults to `{ 'monorepo-lsp' }`.
- `keep` lists other clients, by name, that should start as usual.

In a repo with a config, Neovim would otherwise also start your usual servers
for the same files, on the host. To prevent that, the module asks
`monorepo-lsp --languages` which languages the config covers, once per repo.
Then, for a file whose filetype is one of those, it stops any other client
from starting. Your other configs are left alone, as are the same servers
everywhere else. If the proxy can't answer, the module warns once for that
repo and lets your servers start as usual.

Since Neovim has no hook that runs before a client starts, the module does
this by wrapping `vim.lsp.start`.

The module also lets Neovim run backends' code actions and other commands.
Neovim only runs the commands a server lists when it starts, but the proxy
learns them from its backends later. The module sends the proxy any command
that Neovim doesn't run itself.

With `--eager`, the proxy lists every command when it starts, so Neovim can
run them without the module's help. You can then configure the client
yourself and skip the module, or keep using the module with
`cmd = { 'monorepo-lsp', '--eager' }`. Either works. The module still covers
the commands of any server that took longer than 30 s to start. Since the
module runs `cmd` with `--languages` appended, the proxy accepts
`--eager --languages` and ignores `--eager` there.

If you'd rather not install the module, you can leave the host's servers out
with a repo-local `.nvim.lua` instead, using `'exrc'`:

```lua
vim.lsp.enable({ 'ruby_lsp', 'rubocop' }, false)
```

However, that applies to the whole session, and only if Neovim started in that
repo. You'll also need the `monorepo_lsp` config from
[`lua/monorepo_lsp.lua`](lua/monorepo_lsp.lua) to run commands.

The proxy logs to stderr, which Neovim writes to its LSP log
(`:lua vim.cmd.edit(vim.lsp.log.get_filename())`).

To check the whole setup against the monorepo example, bring up its
containers and run the smoke script from the repo root:

```sh
docker compose --project-directory examples/monorepo up --detach
nvim --clean --headless -l dev/nvim-smoke.lua out/monorepo-lsp
```

If your `nvim` is wrapped by Nix with plugins, it loads them even under
`--clean`. Run the unwrapped binary instead if a plugin gets in the way.

## Agents, through an MCP bridge

Bridges such as [mcp-language-server][1] give an agent a language server's
tools over MCP. Give it the bare command, which serves every server type in
the config, with `--eager`, and the repo root as the workspace:

```sh
mcp-language-server --workspace /path/to/repo --lsp monorepo-lsp -- --eager
```

Without `--eager`, workspace symbol search and references across services
find nothing until the agent opens a file in each service.

I haven't tried this with a bridge yet.

## Known limitations

- Unless you pass `--eager`, backends start when you first open one of their
  files. Until then, workspace symbol search doesn't cover them.
- If a backend stops, it starts again the next time you open one of its
  files. The proxy doesn't send it the files that were already open, which
  you'll need to reopen.
- Copies of files fetched from containers are read-only, and live under
  your temp directory.
- There's no VS Code extension yet, and no release binaries.

[1]: https://github.com/isaacphi/mcp-language-server
