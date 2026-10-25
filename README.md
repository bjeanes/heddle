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

[The config in full](#the-config-in-full) covers every key.

## The config in full

The config has three main tables. `servers` says which language servers handle
which files. `scopes` says how to start each of those servers for the files
under a directory. `runners` holds the commands that start a server somewhere,
such as on the host or in a container. You'll often only need the first two,
because the proxy ships with runners for the host, Docker and Compose. Compose
has no special treatment in the proxy. It's one of those built-in runners,
written in the same TOML you'd use for your own. An optional fourth table,
`cache`, says where to keep [copies of files from the servers](#the-cache).

### Server types and scopes

A server type matches a file by the language ID your editor sends when it
opens the file, by a glob on the file's path from the repo root, or by both.
When several server types handle a file and a request needs a single answer,
the one with the highest `priority` answers. A tie goes to the name that sorts
first.

A scope is a directory relative to the repo root, with `.` for the root itself.
It lists the server types it starts and how to start each one. For each server
type that matches a file, the proxy uses the longest scope that contains the
file and lists that server type. If there isn't one, that server type doesn't
handle the file.

The proxy lays a scope's values over the server type's `boot` table, one key
at a time. That lets you give the command once, on the server type, and only
say where it runs in each scope. Two keys in the result belong to the proxy.
`runner` names the runner to use. `paths` maps directories in the repo to the
absolute directories the server sees, such as where a container mounts them.
Every other key, `command` included, is a value for the runner's templates.

<!-- example: full config -->
```toml
[servers.ruby-lsp]                       # a server type, named as you like
languages = ["ruby"]                     # optional if globs is given
globs = ["**/Gemfile", "**/*.rake"]      # optional if languages is given
priority = 10                            # optional, defaults to 0

[servers.ruby-lsp.boot]                  # optional, defaults to empty
runner = "compose"                       # required, here or in each scope
command = ["bundle", "exec", "ruby-lsp"] # a value for the runner

[servers.rubocop]
languages = ["ruby"]
boot = { command = ["rubocop", "--lsp"] }

[scopes."services/api".ruby-lsp]         # starts ruby-lsp under services/api
service = "api"                          # a value for the runner, which compose requires
paths = { "services/api" = "/app" }      # optional, defaults to each path as itself

[scopes."services/legacy".ruby-lsp]
runner = "host"                          # replaces the server type's runner
command = ["ruby-lsp"]                   # replaces the server type's command

[scopes."."]                             # the repo root, as a catch-all
rubocop = { runner = "compose", service = "lints", paths = { "." = "/src" } }

[cache]                                  # optional, defaults to the system's temp directory
xdg = true                               # or env = "VAR", or path = "/absolute/dir" or "dir/in/the/repo"
```

With this config, a Ruby file under `services/api` gets ruby-lsp in the `api`
service and RuboCop in the `lints` service.

### Runners

A runner is a set of command templates. Each template is a list of arguments,
which the proxy runs directly, without a shell. Every template runs with the
scope's directory as its working directory.

- `start` starts the server, which then speaks LSP over its stdin and stdout.
  Every runner needs one. The proxy runs it the first time you open a file the
  backend handles.
- `prepare` is optional. If the runner has one, the proxy runs it to
  completion before each `start`, for example to start a container for the
  server to run in. If it fails, the backend doesn't start.
- `read` is optional. It prints a file from the server's side. The proxy runs
  it when a server refers to a file that `paths` doesn't cover, such as an
  installed gem, to copy the file out for you to open. Without it, you can't
  open those files.
- `defaults` is optional. It gives values for the templates that a boot config
  can override.

Templates draw on values through placeholders:

| Placeholder          | Value                                                     |
| -------------------- | --------------------------------------------------------- |
| `{cfg:NAME}`         | the boot config's `NAME`, or else the runner's `defaults` |
| `{client:repo}`      | the repo root, as a path on your machine                  |
| `{client:scope}`     | the scope's directory, as a path on your machine          |
| `{server:scope}`     | the scope's directory, as the server sees it              |
| `{read:file}`        | the file to print, as the server sees it (only in `read`) |
| `{proxy:backend_id}` | an ID unique to the backend, safe as a container name     |

A `{cfg:…}` value can be a string or a list of strings. The other placeholders
are always a single string. Since there's no shell to split a string into
words, a list is how you pass several arguments. A list has to make up a whole argument
of the template, and its items become separate arguments. An empty list leaves
the argument out. A `{cfg:…}` value can itself use the other placeholders, as
in `"{client:repo}:/src"`, though not another `{cfg:…}`.

When the proxy loads the config, it checks every scope against its runner. It
reports a value the runner needs but nobody gives, an unknown placeholder, or
`paths` that don't cover the scope's directory. It warns about a value that the
runner's templates never use, which is often a misspelt key.

`prepare` is for anything that has to be in place before the server can start.
The built-in `docker-run` makes the server the container's main process. If
you'd rather start the container first and run the server inside it, add a
runner whose `prepare` starts the container, and name it from a scope:

<!-- example: custom runner -->
```toml
[runners.docker-detached]                        # a runner, named as you like
defaults = { args = [] }                         # optional
prepare = ["docker", "run", "--rm", "--detach", "--name", "{proxy:backend_id}", "{cfg:args}", "{cfg:image}", "sleep", "infinity"]
start = ["docker", "exec", "--interactive", "--workdir", "{server:scope}", "{proxy:backend_id}", "{cfg:command}"]
read = ["docker", "exec", "{proxy:backend_id}", "cat", "{read:file}"]

[scopes."tools/cli".ruby-lsp]
runner = "docker-detached"
image = "ruby:3.3"
args = ["--volume", "{client:repo}:/src"]
paths = { "." = "/src" }
```

Added to the config above, this starts a `ruby:3.3` container with the repo
mounted at `/src`, then runs `bundle exec ruby-lsp` in it from
`/src/tools/cli`. Since `docker run --detach` returns once the container has
started, the container is ready by the time `start` runs.

However, the proxy has no teardown step yet. When the backend exits, the
container keeps running until you `docker stop` it. Only then does `--rm`
remove it.

### The built-in runners

The proxy adds these to your config's `runners`. If you define a runner with
the same name, yours replaces the built-in one. Since there's no default
runner, every boot config has to name one.

<!-- resources/monorepo_lsp/runners.toml -->
```toml
# The runners that ship with the proxy. A project's config can override any of
# these by name, or add its own.

[runners.host]
start = ["{cfg:command}"]

[runners.compose]
defaults = { compose = ["docker", "compose"] }
start = ["{cfg:compose}", "exec", "-T", "--workdir", "{server:scope}", "{cfg:service}", "{cfg:command}"]
read = ["{cfg:compose}", "exec", "-T", "{cfg:service}", "cat", "{read:file}"]

[runners.docker-exec]
start = ["docker", "exec", "--interactive", "--workdir", "{server:scope}", "{cfg:container}", "{cfg:command}"]
read = ["docker", "exec", "{cfg:container}", "cat", "{read:file}"]

[runners.docker-run]
defaults = { args = [] }
start = ["docker", "run", "--rm", "--interactive", "--name", "{proxy:backend_id}", "{cfg:args}", "--workdir", "{server:scope}", "{cfg:image}", "{cfg:command}"]
read = ["docker", "exec", "{proxy:backend_id}", "cat", "{read:file}"]
```

Every one of them needs a `command`.

- `host` runs it on your machine.
- `compose` runs it in a running Compose `service`. To pass Compose flags such
  as `--file`, set `compose`, which defaults to `["docker", "compose"]`.
- `docker-exec` runs it in a running `container`.
- `docker-run` starts a new container from `image` for each backend, and
  removes it when the server exits. Since `paths` only translates paths, you
  mount the repo yourself with `args`, as in
  `args = ["--volume", "{client:repo}:/src"]`.

### The cache

When a server points you at a file that `paths` doesn't cover, such as an
installed gem, the proxy copies it out with the runner's `read` for you to
open. It keeps those copies under `<root>/monorepo-lsp/<repo>/`, where `<root>`
is the system's temp directory (`TMPDIR`) unless the `cache` table says
otherwise. Each server refreshes a file the first time it needs it after
starting. A stale copy therefore only lasts until then.

`cache` takes one of three keys:

- `xdg = true` follows the [XDG Base Directory spec](https://specifications.freedesktop.org/basedir-spec/latest/). The root is
  `XDG_CACHE_HOME` if it's set to an absolute path, and `~/.cache` otherwise.
  It works the same on every OS, macOS included. If `HOME` isn't usable
  either, the proxy warns you and uses the temp directory.
- `env` names an environment variable that holds the root. If the variable is
  unset, empty or a relative path, the proxy warns you and uses the temp
  directory.
- `path` gives the root itself, either absolute or relative to the directory
  holding the config. Since `~` isn't expanded, use `xdg` or `env` for a
  directory in your home.

Each choice has its trade-offs:

- **The temp directory** needs no setup, and the OS clears it from time to
  time. However, some tools point `TMPDIR` at a new directory for each shell,
  such as `nix develop`. An editor started from one of those shells starts a
  new cache each time and leaves the old ones behind.
- **`xdg = true`** is the one I'd recommend, since it stays put across shells
  and needs no setup on anyone's machine. That makes it a good fit for a config
  the whole team shares. Nothing sweeps it, though. Since the proxy only copies
  files that servers point you at, and replaces each copy rather than adding
  another, it stays small. However, it does stay.
- **An environment variable** suits a root that differs from machine to
  machine. Everyone who uses the config has to set it where their editor will
  inherit it, or the proxy will warn and use the temp directory.
- **An absolute path** is the same for everyone who uses the config. That's
  awkward in a config checked into the repo, since machines lay out their
  directories differently.
- **A path in the repo** needs an entry in `.gitignore`. Since it's inside the
  repo, tools that search or watch the repo will see it too, unless they
  ignore it.

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

[KNOWN-ISSUES.md](KNOWN-ISSUES.md) lists them all. The ones you're most
likely to run into are:

- Unless you pass `--eager`, backends start when you first open one of their
  files. Until then, workspace symbol search doesn't cover them.
- If a backend stops, it starts again the next time you open one of its
  files. The proxy doesn't send it the files that were already open, which
  you'll need to reopen.
- A backend that hangs while starting holds up every request for its files,
  with no timeout.
- Copies of files fetched from containers are read-only. Nothing cleans them
  up, wherever [the cache](#the-cache) keeps them.
- There's no VS Code extension or published release yet.

[1]: https://github.com/isaacphi/mcp-language-server
