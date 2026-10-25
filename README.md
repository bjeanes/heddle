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

For more, see the examples:

- [`examples/ruby-compose`](examples/ruby-compose) is a single Ruby service.
- [`examples/monorepo`](examples/monorepo) has two Ruby services with their
  own bundles, plus RuboCop in a separate `lints` service that checks the
  whole repo.

## Neovim

This needs Neovim 0.11 or newer, though I've only tried it with 0.12.6. Add
this to your config.

```lua
vim.lsp.config('monorepo_lsp', {
  cmd = { 'monorepo-lsp' },
  root_markers = { '.monorepo-lsp.toml' },
  workspace_required = true,
  on_init = function(client)
    -- Neovim only runs the commands a server lists when it starts, but the
    -- proxy learns its backends' commands later. Send it every command that
    -- Neovim doesn't run itself.
    setmetatable(client.commands, {
      __index = function(_, command)
        if not vim.lsp.commands[command] then
          return function(cmd, ctx)
            client:request('workspace/executeCommand', { command = command, arguments = cmd.arguments }, nil, ctx.bufnr)
          end
        end
      end,
    })
  end,
})
vim.lsp.enable('monorepo_lsp')

-- In a repo with a .monorepo-lsp.toml, leave these to the proxy.
for _, name in ipairs({ 'ruby_lsp', 'rubocop' }) do
  vim.lsp.config(name, {
    root_dir = function(bufnr, on_dir)
      if not vim.fs.root(bufnr, '.monorepo-lsp.toml') then
        on_dir(vim.fs.root(bufnr, { 'Gemfile', '.git' }))
      end
    end,
  })
end
```

The proxy only starts in a repo with a `.monorepo-lsp.toml`. Add any other
servers the repo's config covers to the list at the end, so that Neovim
doesn't also start them on the host.

`on_init` is there for code actions and other commands. Without it, Neovim
refuses to run any backend's commands, since the proxy can only list them
after Neovim has started it.

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
the config, and the repo root as the workspace:

```sh
mcp-language-server --workspace /path/to/repo --lsp monorepo-lsp
```

I haven't tried this with a bridge yet.

## Known limitations

- Backends start when you first open one of their files. Until then,
  workspace symbol search doesn't cover them.
- If a backend stops, it starts again the next time you open one of its
  files. The proxy doesn't send it the files that were already open, which
  you'll need to reopen.
- Copies of files fetched from containers are read-only, and live under
  your temp directory.
- There's no VS Code extension yet, and no release binaries.

[1]: https://github.com/isaacphi/mcp-language-server
