# heddle

Editors and agents expect to start a project's language servers themselves,
on the host, one per language. Plenty of projects don't fit that. Their
dependencies might only be installed in a container, or two parts of the repo
might need different versions of the same server.

`heddle` is a central place to define a project's language servers, in a
config checked into the repo, for any editor or agent to use. To your editor,
or to an agent's MCP bridge, heddle is one ordinary language server. Behind
that, it starts the right servers for each part of the repo, wherever they
need to run, such as on the host or inside each service's Compose container.
It translates paths in both directions. When a server points at a file that
only exists where it runs, such as an installed gem, heddle copies the file
out so you can open it.

A heddle is the part of a loom that lifts _selected_ warp threads for the weft
to pass between, much as heddle picks out the servers that match each file.

[KICKOFF.md](KICKOFF.md) covers why it exists and
[DESIGN.md](DESIGN.md) covers how it works.

## Why you might use it

- **A multi-service monorepo.** Each service's servers run in that service's
  own container, with the language and gem versions it actually uses. Two Ruby
  services on different Ruby versions each get a ruby-lsp of their own.
- **A single polyglot app**, such as Rails with TypeScript, Tailwind and
  ESLint. Your editor configures one server, heddle, instead of four. It sends
  each file to every server that handles it, and merges their diagnostics and
  code actions into one answer.
- **One config for the whole team.** The servers are defined once, in the
  repo. Whoever opens it gets the same servers, whichever editor they use.
  (VS Code still needs an extension to start it, which doesn't exist yet.)
- **Agents.** MCP bridges such as mcp-language-server start one server for
  one workspace. heddle is how an agent gets every language in the repo
  through that one server. Since `heddle serve --eager` starts every server up
  front, workspace symbol search works from the agent's first request.
- **A containerised or remote toolchain, even for one service.** The app runs
  in Docker, in a Nix shell or over SSH, while your editor stays on the host.
  Docker and Compose have built-in runners. For anything else, you write a
  runner that wraps the command, such as `nix develop --command` or `ssh`.
  Since heddle maps paths between the two sides and fetches files that only
  exist on the server's side, go-to-definition keeps working inside your
  installed packages.
- **Pinned versions.** Each server runs from the project's own bundle or
  environment. You get the versions the project pins, instead of whatever
  happens to be installed globally.

## Why you might not

- **It's brand new.** heddle is an early prototype. So far it has only been
  tried with ruby-lsp and RuboCop running in Docker Compose on macOS, from
  Neovim 0.12 and from the session driver in `dev/`. Expect rough edges.
- **It was built with AI.** Most of the code was written by Claude Code, an
  AI coding agent, with me directing the design. It has tests, but it hasn't
  had the scrutiny of a project that people have relied on for years.
- **Your current setup may be fine.** If your editor already starts the
  servers you need on the host, and they work, heddle adds a process between
  your editor and those servers for little in return. It's for the
  scenarios above.
- **It's one more layer to debug.** When something goes wrong, the cause
  could be the server, the runner that starts it, or heddle itself.

## Installing it

Each [GitHub release](https://github.com/bjeanes/heddle/releases) has a
binary for macOS arm64 (`darwin-arm64`), x86_64 Linux (`linux-amd64`) and
arm64 Linux (`linux-arm64`). So far, the only release is the
[`edge`](https://github.com/bjeanes/heddle/releases/tag/edge) prerelease,
which is rebuilt from every push to `main`.

Use `curl` rather than your browser if you're on macOS. The macOS binary isn't
signed, and browsers mark what they download as quarantined. macOS then
refuses to run it, saying that Apple could not verify heddle is free of
malware. curl doesn't set that flag. If you've already downloaded it through a
browser, clear the flag with `xattr -d com.apple.quarantine heddle`.

The `edge` archives are named for the commit, as `git describe` gives it, such
as `heddle-e448ed6-darwin-arm64.tar.gz`. Since the name changes with every
build, there's no fixed URL to download. Instead, this asks GitHub's API for
the release's files, picks out the one for your platform, and unpacks the
`heddle` binary into the current directory. Set `platform` to `darwin-arm64`,
`linux-amd64` or `linux-arm64` first:

```sh
platform=darwin-arm64
curl -fsSL https://api.github.com/repos/bjeanes/heddle/releases/tags/edge \
  | grep -o "https://[^\"]*-$platform\.tar\.gz" \
  | xargs curl -fL \
  | tar -xz
```

A tagged release names its archives for the version, so you can write the link
out yourself, as in
`https://github.com/bjeanes/heddle/releases/download/v0.1.0/heddle-v0.1.0-darwin-arm64.tar.gz`.
For any release, you can also copy the archive's link from the release page and
run `curl -fL <link> | tar -xz`.

## Building it

To build it yourself, you need Nix with flakes. The dev shell provides Jolt,
for aarch64 macOS and x86_64 Linux.

```sh
nix develop
jolt build -m heddle.main -o out/heddle
```

That writes a single binary to `out/heddle`. Put it on your `PATH`,
or point your editor at it directly.

To build it and install it as `~/bin/heddle` in one go, run `install-heddle`
from the repo root. Since the command comes with the dev shell, run it inside
`nix develop`, or with direnv loading the shell for you. Give it a directory
to install somewhere else, as in `install-heddle ~/.local/bin`.

## Configuring a repo

The proxy looks for `.heddle.toml` at the root of the workspace your
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

To check a config, run `heddle check` from the repo root, or give it the
root as an argument. It prints the config's errors and warnings, and exits
non-zero if there are any errors. `heddle languages` takes the same
argument and prints which languages the config covers, as JSON such as
`{"languages":["ruby"]}`.

## Running it

```
heddle [serve] [--eager] [--trace FILE] [SERVER-TYPE...]
heddle languages [DIR [SERVER-TYPE...]]
heddle check [DIR]
heddle init [DIR]
heddle help
```

Editors and bridges run `heddle` on its own, or with `--eager`. Either
way, it's short for `heddle serve`, which speaks LSP over stdin and
stdout and serves every server type in the config. To serve only some of them,
name them after `serve`, as in `heddle serve ruby-lsp rubocop`. Without
the `serve`, a name would be taken for a command. Given server types after its
directory, `languages` only prints the languages of those. If one of them isn't
in the config, `languages` fails, whereas `serve` warns once the editor
connects and serves the rest.

If there's no `.heddle.toml`, `serve` fails the editor's `initialize` request
with an error saying where it looked. If the config is invalid, or no scope in
it starts a server type, `serve` still answers the editor, shows it a message
saying what's wrong, and serves nothing. Run by hand at a terminal, though, it
prints the problem to stderr and exits 1 instead of waiting on stdin. It does
the same there if none of the server types named after `serve` is in the
config.

`heddle init` writes a starter `.heddle.toml` to the root of DIR. It walks
through a server type, a scope on the host, a scope in a Compose service, a
runner of your own, the cache, and tracing, all commented out. Its notes start
with `## ` and its examples with `# `. Uncomment what you need, then run
`heddle check`. If there's already a config there, `init` leaves it alone and
exits 1.

`heddle help` prints the usage to stdout and exits 0. Any other command
line it can't make sense of prints the problem and the usage to stderr, and
exits 2.

### Tracing messages

When a client doesn't behave as you expect, it helps to see exactly what it
sends. `heddle serve --trace FILE` appends every LSP message to FILE. That
covers what the client sends heddle, what heddle sends the client, and what
heddle sends each server or receives from it. Messages to and from a server
have paths as the server sees them. Setting `HEDDLE_TRACE=FILE` in heddle's
environment does the same, for clients whose config makes arguments awkward.

Each line of FILE is a JSON object with the `time` in milliseconds since the
epoch, the `message`, and who it came `from` or went `to`. That's either
`client` or a backend's ID, such as `heddle-1a2b3c4d`. A backend's lines also
give its `serverType` and `scope`. For example, to list what the client sent,
or everything exchanged with `ruby-lsp`:

```sh
jq -c 'select(.from == "client") | [.time, .message.method, .message.id]' trace.jsonl
jq -c 'select(.serverType == "ruby-lsp")' trace.jsonl
```

If heddle can't write to FILE, it says so once on stderr and goes on serving
without a trace.

To trace a project without changing how its clients start heddle, name the
file in the config's `trace` table instead:

```toml
[trace]
path = "heddle-trace.jsonl"              # absolute, or relative to the config's directory
```

Since heddle only reads the config when the client sends `initialize`, the
trace starts with that `initialize`. `--trace` and `HEDDLE_TRACE` override the
config.

## Starting servers eagerly

By default, the proxy starts each server the first time you open one of its
files. That keeps an editor from starting every container's server when you
only open one file. However, until then, the proxy can't tell the client
about that server's features, and workspace symbol search doesn't cover it.

`heddle --eager` (or `heddle serve --eager ruby-lsp`, naming
server types as usual) starts every server in the config as soon as the client
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
An optional fifth, `trace`, names a file to [trace messages](#tracing-messages)
to.

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

[trace]                                  # optional, defaults to no trace
path = "heddle-trace.jsonl"              # or "/absolute/file"
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
  server to run in. If it fails, the backend doesn't start. A backend has
  120 s to start, which covers `prepare` as well as the server answering
  `initialize`, so `prepare` can't take all of it.
- `read` is optional. It prints a file from the server's side. The proxy runs
  it when a server refers to a file that `paths` doesn't cover, such as an
  installed gem, to copy the file out for you to open. Without it, you can't
  open those files. Each `read` has 10 s to finish.
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

<!-- resources/heddle/runners.toml -->
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
open. It keeps those copies under `<root>/heddle/<repo>/`, where `<root>`
is the system's temp directory (`TMPDIR`) unless the `cache` table says
otherwise. `<repo>` is the repo directory's name and a hash of its path, which
keeps apart the caches of repos sharing a root. Under it, each copy is at
`<server type>/<scope>/=/<path on the server>`, such as
`ruby-lsp/services/api/=/usr/lib/ruby/set.rb`. Each server refreshes a file
the first time it needs it after starting. A stale copy therefore only lasts
until then.

`cache` takes one of three keys:

- `xdg = true` follows the [XDG Base Directory spec](https://specifications.freedesktop.org/basedir-spec/latest/). The root is
  `XDG_CACHE_HOME` if it's set to an absolute path, and `~/.cache` otherwise.
  It works the same on every OS, macOS included. If `HOME` isn't usable
  either, the proxy warns you and uses the temp directory.
- `env` names an environment variable that holds the root. If the variable is
  unset, empty or a relative path, the proxy warns you and uses the temp
  directory.
- `path` gives the cache directory itself, either absolute or relative to the
  directory holding the config. The copies go straight in it, without
  `heddle/<repo>/`, since only this repo's config names it. As `~` isn't
  expanded, use `xdg` or `env` for a directory in your home.

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
  directories differently. Several checkouts of the repo would also share the
  one directory, each replacing the others' copies.
- **A path in the repo** needs an entry in `.gitignore`. Since it's inside the
  repo, tools that search or watch the repo will see it too, unless they
  ignore it.

## Neovim

This needs Neovim 0.11 or newer, though I've only tried it with 0.12.6. The
repo is also a Neovim plugin. Install it with your plugin manager, such as
lazy.nvim, pointed at a checkout:

```lua
{
  dir = '/path/to/heddle',
  config = function()
    require('heddle').setup()
  end,
}
```

Or add a checkout to your runtimepath yourself:

```lua
vim.opt.runtimepath:append('/path/to/heddle')
require('heddle').setup()
```

`setup()` configures and enables a `heddle` client, which only starts in
a repo with a `.heddle.toml`. It takes these options:

- `cmd` runs the proxy, and defaults to `{ 'heddle' }`. Its first item
  has to be the proxy itself, rather than a wrapper such as `nix run`, since
  the module runs it with `languages` too.
- `keep` lists other clients, by name, that should start as usual.

In a repo with a config, Neovim would otherwise also start your usual servers
for the same files, on the host. To prevent that, the module runs
`heddle languages` (with the program from `cmd`) to ask which languages
the proxy serves, once per repo. If `cmd` names server types after `serve`,
only their languages count.
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
`cmd = { 'heddle', '--eager' }`. Either works. The module still covers
the commands of any server that took longer than 30 s to start.

If you'd rather not install the module, you can leave the host's servers out
with a repo-local `.nvim.lua` instead, using `'exrc'`:

```lua
vim.lsp.enable({ 'ruby_lsp', 'rubocop' }, false)
```

However, that applies to the whole session, and only if Neovim started in that
repo. You'll also need the `heddle` config from
[`lua/heddle.lua`](lua/heddle.lua) to run commands.

The proxy logs to stderr, which Neovim writes to its LSP log
(`:lua vim.cmd.edit(vim.lsp.log.get_filename())`).

To check the whole setup against the monorepo example, bring up its
containers and run the smoke script from the repo root:

```sh
docker compose --project-directory examples/monorepo up --detach
nvim --clean --headless -l dev/nvim-smoke.lua out/heddle
```

If your `nvim` is wrapped by Nix with plugins, it loads them even under
`--clean`. Run the unwrapped binary instead if a plugin gets in the way.

### Installing the module with Nix

The flake's `packages.<system>.vim-plugin` output is the module, built as a
Neovim plugin. Add heddle to your flake's inputs:

```nix
inputs.heddle.url = "github:bjeanes/heddle";
```

Then add the plugin to Neovim. With home-manager, where `heddle` is that
input, that looks like this:

```nix
programs.neovim.plugins = [
  {
    plugin = heddle.packages.${pkgs.stdenv.hostPlatform.system}.vim-plugin;
    type = "lua";
    config = "require('heddle').setup()";
  }
];
```

The flake doesn't build the `heddle` binary yet. You'll still need to install
it separately, such as with `install-heddle` or a release binary (see
[Installing it](#installing-it)).

## Claude Code

Claude Code talks to language servers through plugins. The repo is a
marketplace with one plugin in it, which runs `heddle serve --eager`. Add the
marketplace from GitHub, or give the path to a checkout in place of
`bjeanes/heddle`. Then install the plugin from it:

```
/plugin marketplace add bjeanes/heddle
/plugin install heddle@heddle
```

Claude Code copies the plugin into its own cache when you install it. If you
change the plugin, run `/plugin marketplace update heddle` and install it
again. You can also load it straight from a checkout with
`claude --plugin-dir /path/to/heddle/claude-code`, unless your organisation's
managed settings forbid it.

The plugin gives Claude its `LSP` tool, for definitions, references, hover,
symbols and the like. Claude also sees the servers' diagnostics after each
edit.

### Enabling it only where you need it

The plugin claims about 40 file extensions. Claude Code gives each extension
to the first plugin that registers it. Since heddle fails `initialize` in a
repo without a `.heddle.toml`, a plugin enabled everywhere takes those files
from your other language servers and then serves nothing. Instead, turn it off
in your user settings. Then turn it on in `.claude/settings.local.json` in each
repo that has a config. Turn off any plugin that covers the same files there
too, such as the official ruby-lsp plugin:

```json
{
  "enabledPlugins": {
    "heddle@heddle": true,
    "ruby-lsp@claude-plugins-official": false
  }
}
```

If you leave the other plugin on, `/plugin` shows an error such as `LSP server
"heddle" is not used for .rb files`.

Because of `--eager`, workspace symbol search covers every service from the
start. However, heddle waits up to 30 s for the servers before it answers
`initialize`. Until then, Claude Code reports that the server is starting.

### Letting Claude open cached files

Claude Code drops go-to-definition results that point into a git-ignored
directory. I
confirmed from a [trace](#tracing-messages) that heddle had returned the
location. Hover still showed it as a link. Since a cache in the repo has to be
ignored, Claude can only follow a server into an installed gem when
[the cache](#the-cache) is outside the repo. `xdg = true` keeps it in
`~/.cache/heddle`:

```toml
[cache]
xdg = true
```

Claude also needs permission to read it. Add the directory to
`permissions.additionalDirectories` in the same settings file. If the session
runs in Claude Code's sandbox, or your organisation limits reads outside the
working directory, check that those settings let Claude read it too. I haven't
confirmed which keys that takes.

## Agents, through an MCP bridge

For Claude Code, use [the plugin](#claude-code) instead. Adding heddle itself
as an MCP server, as in `claude mcp add heddle -- heddle serve`, doesn't work.
MCP is a different protocol from the LSP that heddle speaks. When I tried it,
Claude Code gave up waiting after 30 s.

For other agents, bridges such as [mcp-language-server][1] give an agent a
language server's tools over MCP. Give it the bare command, which serves every
server type in the config, with `--eager`, and the repo root as the workspace:

```sh
mcp-language-server --workspace /path/to/repo --lsp heddle -- --eager
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
  files, and no sooner than 30 s after it stopped.
- Copies of files fetched from containers are read-only. Nothing cleans them
  up, wherever [the cache](#the-cache) keeps them.
- There's no VS Code extension yet. The only release is `edge`, built from
  `main`.

[1]: https://github.com/isaacphi/mcp-language-server
