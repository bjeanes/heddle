# monorepo-lsp: design

This describes how the proxy is meant to work. `KICKOFF.md` covers why it exists
and what it's for. Nothing here is built yet. All of it is a proposal, open to
change as we learn.

## Terms

These words have a specific meaning in the rest of this doc.

- A **client** is whatever starts the proxy and talks LSP to it, such as an
  editor or an MCP bridge for an agent.
- A **server type** is a kind of language server the config knows about, such as
  `ruby-lsp`, along with which files it handles.
- A **scope** is a directory in the repo, along with how each server type should
  be started for files under it.
- A **runner** is a named set of command templates for starting a server
  somewhere, such as on the host or in a container.
- A **boot config** is everything needed to start one server type in one scope.
  It's the server type's `boot` table, with the scope's values over the top.
- A **backend** is one language server process that the proxy started, for one
  server type in one scope. Each backend has a **backend ID**, unique among
  everything running at the time.
- A **client path** is a file or directory as the client sees it, on the host.
  A **server path** is the same file or directory as the backend sees it. The
  proxy translates between the two using the scope's `paths`.
- A **fetched file** is a file a backend refers to that no entry in `paths`
  covers, such as a library installed in a container. The proxy copies it to the
  host so the client can open it. The copy is read-only.

## Invocation

Clients differ in how much they know about the servers behind the proxy. An
editor extension built around one server may want to send that server's custom
requests, and expects to talk to that server alone. A generic language client,
or an MCP bridge for an agent, wants one connection for the whole repo. That
matters for agents in particular. Running one bridge per language would put a
copy of the same tools into the agent's context for every language, with names
that are easy to confuse.

So, the proxy can be run either way. Running `monorepo-lsp` on its own, which
is short for `monorepo-lsp serve`, serves every server type in the config.
Naming server types, as in `monorepo-lsp serve ruby-lsp` or
`monorepo-lsp serve ruby-lsp rubocop`, serves only those. Both are the same
code. The names only filter which server types the proxy will start.

The bare command is what editors and bridges run, so it has to mean `serve`.
However, a server type can be named anything, including the name of another
command. So, a bare word in first position is always a command, and server
types can only be named after `serve`. An unknown command is an error. Since
the word could be a misspelt command or a server type missing its `serve`, the
error doesn't guess which. It only points out where server types go.

The proxy finds its config by looking for `.monorepo-lsp.toml` in the workspace
root the client sends at startup, falling back to the directory it was started
in. The file name will likely change along with the project's name.

`monorepo-lsp languages [DIR]` doesn't start the proxy. Instead, it prints
the languages that the config at the root of DIR covers, as JSON such as
`{"languages":["ruby"]}`. DIR defaults to the directory it was started in. Only
server types that some scope starts count. If there's no config, or it's
invalid, it prints the problems to stderr and exits non-zero. A client uses
this to stop its own servers for those languages from starting alongside the
proxy, since few clients can parse TOML themselves.

`monorepo-lsp check [DIR]` reads the same config and prints its errors and
warnings, exiting non-zero if there are errors. That way, someone editing the
config can see its problems without starting an editor.

`monorepo-lsp help`, `--help` or `-h` prints the usage to stdout and exits 0.
A command line it can't parse is a usage error instead. It prints the error and
the usage to stderr, and exits 2.

## Configuration

The Neovim harness answered two questions at once, which the config keeps
apart:

1. _Which_ servers should handle a file? That depends on the file's language or
   name. A Ruby file might get both ruby-lsp and RuboCop.
2. _How_ should each of those servers be started for that file? That depends on
   where in the repo the file is. The same server might run in one container for
   one service, a different container for another, and on the host for a third.
   Its command can differ between them too.

The config has a section for each. **Server types** answer the first question
and **scopes** answer the second. A scope names a **runner** for each server
type it starts, which decides where and how the server process runs.

```toml
[servers.ruby-lsp]
languages = ["ruby"]
globs = ["**/Gemfile", "**/*.rake"]
boot = { command = ["bundle", "exec", "ruby-lsp"] }

[servers.rubocop]
languages = ["ruby"]
boot = { command = ["rubocop", "--lsp"] }

[servers.typescript]
languages = ["typescript", "typescriptreact"]
boot = { command = ["typescript-language-server", "--stdio"] }

[scopes."services/api"]
ruby-lsp = { runner = "compose", service = "api", paths = { "services/api" = "/app" } }

[scopes."services/billing"]
ruby-lsp = { runner = "compose", service = "billing", paths = { "services/billing" = "/app" } }

# This service's Ruby is too old for ruby-lsp. A newer Ruby on the host runs it.
[scopes."services/legacy"]
ruby-lsp = { runner = "host", command = ["ruby-lsp"] }

[scopes."services/web"]
typescript = { runner = "docker-run", image = "web-tools", paths = { "." = "/src" }, args = ["--volume", "{client:repo}:/src"] }

[scopes."."]
rubocop = { runner = "compose", service = "lints", paths = { "." = "/src" } }
```

### Server types

A server type matches a file by its language, its path, or both. `languages`
holds language IDs, which the editor sends when it opens a file. Editors are
already good at recognising languages, including from file names such as
`Rakefile` or from a shebang line. Where they aren't, `globs` match on the path.
Later requests identify a file only by its URI. The proxy therefore remembers
each open file's language.

A server type's `boot` table holds defaults for starting it. Scopes override
them one key at a time. That avoids repeating a command in every scope, without
tying the command to the server type. Any scope can still replace it.

A server type's optional `priority` decides which backend answers when several
handle a file. "Requests about one file" below covers how.

### Scopes

Each scope is keyed by a directory, relative to the repo root, with `.` as the
root itself. A scope starts one backend for each server type it lists, rooted at
that directory. To run one backend per service, list each service as its own
scope.

For each server type that matches a file, the proxy uses the scope with the
_longest_ path that contains the file and starts that server type. If no scope
starts a server type for a file, that server type doesn't handle the file. For
a catch-all, give the server type a scope at `.`, as `rubocop` has above.

Taking `services/api/app/models/user.rb` from the example above, its language is
`ruby`. That matches both `ruby-lsp` and `rubocop`.

- For `ruby-lsp`, the longest scope is `services/api`. It runs
  `bundle exec ruby-lsp` in the `api` Compose service, rooted at `/app`.
- For `rubocop`, only `.` starts it. It runs `rubocop --lsp` in the `lints`
  service, rooted at `/src`.

## Runners

A runner describes how to start a server somewhere, whether that's on the host,
in a container, or anywhere else a command can reach. The proxy itself knows
nothing about any particular runner. Every runner, including the ones that ship
with the proxy, is defined in config as a set of command templates.

- **`start`** starts the server, which then talks LSP over its stdin and stdout.
- **`read`** prints a file from the server's side. The proxy runs it to get
  fetched files. It's optional. Without it, a backend's fetched files can't be
  opened.
- **`prepare`** runs before `start`, for example to bring up a stopped
  container. It's optional too.

These are the runners that ship with the proxy, from
`resources/monorepo_lsp/runners.toml`:

```toml
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

A project's config can override any of these by name, or add its own, such as
one for Podman, `kubectl exec` or `nix develop --command`. Projects that only
use the built-ins don't need to repeat them, and they pick up fixes to them. A
command that prints the built-in definitions would make them easy to copy from.

`docker-exec` and `docker-run` show why an image and a container need separate
values. `docker exec` acts on a container that already exists, which the scope
names with `container`. `docker run` creates a new container from the scope's
`image`. The proxy names that new container after the backend's ID. `read` can
then find it later, and two clients running the same config won't fight over
one name.

As I understand it, `compose exec -T` turns off Compose's pseudo-terminal while
leaving stdin attached, which the protocol needs. The spike should confirm that.

### Placeholders

Every placeholder has the form `{owner:name}`. The owner says where the value
comes from.

| Placeholder          | Value                                                         |
| -------------------- | ------------------------------------------------------------- |
| `{cfg:…}`            | a value from the boot config, or from the runner's `defaults` |
| `{client:scope}`     | the scope's directory, as a client path                       |
| `{server:scope}`     | the scope's directory, as a server path                       |
| `{client:repo}`      | the repo root, as a client path                               |
| `{read:file}`        | the fetched file to print, as a server path                   |
| `{proxy:backend_id}` | the backend's ID                                              |

`client:` and `server:` placeholders are always paths. `read:` placeholders
only exist in `read`, and the proxy reports using one anywhere else as an error
when it loads the config. Anything else the proxy provides goes under `proxy:`.

A boot config names its `runner` and gives it `paths`. Every other key in it,
including `command`, is a value for `{cfg:…}`. When the proxy loads the config,
it checks each scope against its runner's templates. If a template uses a
`{cfg:…}` value that neither the boot config nor the runner's `defaults` has,
that is an error. A `compose` scope with no `service` is one example.

The proxy only provides values that exist for every valid scope. That is why
there is no `{server:repo}`. The repo root only has a server path if `paths`
happens to cover it, which many scopes won't. The scope's directory is
different. A backend has to start there, which means a scope whose `paths`
don't cover its directory is already an error.

The backend ID is the project's name followed by something unique to the
backend, such as `monorepo-lsp-3f9a1c`. It only uses characters that are safe
in a Docker container name. Any containers left behind after a crash can then
be traced back to the proxy.

A value can be a string or a list of strings. A list has to make up a whole
element of the template, and its items are spliced in. That is how
`{cfg:command}` and `{cfg:compose}` become several arguments without going
through a shell. An empty list removes the element entirely, which covers
optional flags such as `{cfg:args}` above. A list placeholder inside a longer
string is an error. Wrappers such as ssh and `sh -c` need the server's command
as a single shell-quoted string. We can add a way to join a list like that if
one of them turns out to be needed.

### Paths

`paths` maps client paths to server paths, by directory. Its keys are relative
to the repo root and its values are absolute. There can be several entries. The
longest match wins in either direction. If a boot config has no `paths`, every
path maps to itself. That is right for the `host` runner. It's also right for
containers that mount the repo at the same path as on the host. If someone
forgets `paths` for a container that mounts it elsewhere, the server will fail
early on paths that don't exist.

The proxy works out `{server:scope}` by translating the scope's directory
through `paths`, using the same longest-match rule. An entry only has to
_contain_ the scope's directory. A container with the whole repo mounted
therefore needs only one entry. For a scope at `services/api`:

| `paths`                                     | `{server:scope}`    |
| ------------------------------------------- | ------------------- |
| `{ "services/api" = "/app" }`               | `/app`              |
| `{ "." = "/src" }`                          | `/src/services/api` |
| `{ "." = "/src", "services/api" = "/app" }` | `/app`              |
| `{ "services/web" = "/app" }`               | (an error)          |

Since keys are distinct directories, and a nested one is always longer than its
parent, there is never a tie. The scope's directory is also the root the proxy
gives the backend at startup.

A container's `paths` usually only need to cover its bind mounts. When a
backend refers to anything else, such as a library installed in a named volume
or in the image, that becomes a fetched file. Since the proxy only has a copy,
fetched files are read-only. Editing a file needs the real one, under an entry
in `paths`. In practice, the files anyone edits are in the bind-mounted source
anyway.

`docker-run` is the one built-in where `paths` and the runner's arguments
overlap. Its mounts have to be listed in `args`, repeating what `paths` already
says, as in the `services/web` scope above. I've accepted the repetition for
now.

The Neovim harness worked its mappings out from `docker inspect` instead. That
spared writing them down, but it tied the harness to Docker. With the mappings
declared in the config, every runner has the same shape.

## Routing messages

The proxy sits in the middle of every message. Most of the design work is in
handling the ones that don't map neatly onto a single backend.

The proxy relies only on what the LSP specification defines, checking what each
client says it supports before using an optional feature. We're prototyping
against Neovim and VS Code, but nothing below should depend on either.

### Startup and capabilities

When a client starts a language server, it sends `initialize` and waits for the
server's capabilities before sending anything else. The proxy has to answer
before it knows which backends it will need.

The proxy answers `initialize` itself. It announces text document syncing for
every file, which means the client tells it about every file that opens. Then,
as backends start, the proxy registers their features with the client
(`client/registerCapability`[1]), limited to their files with a document
selector.

Each feature gets one registration, covering the files of every backend that
offers it. Clients such as VS Code create a provider for each registration, and
ask every provider whose selector matches a file. With a registration per
backend, the client would send the proxy the same request once for each backend
that handles the file. Since the proxy already routes or combines each request
itself, the client would then show every answer several times. The registration
merges the backends' options, such as taking every backend's trigger characters.
When another backend starts and the registration has to cover more, the proxy
unregisters it and registers it again. As far as I can tell, the spec doesn't
say whether a client should replace a registration whose ID is registered a
second time.

Clients say, feature by feature, whether they accept registrations after
startup. For a feature the client can't register later, the proxy announces it
at startup and answers with an empty result whenever no backend for the file
offers it.

Starting backends lazily has its costs, though. Some clients only use what a
server announced when it started. Neovim, for example, only runs the commands
listed in the `initialize` reply. An agent's MCP bridge would find no
workspace symbols, or references in other services, until some file had been
opened. Semantic tokens and on-type formatting can't be announced at all,
since only a backend can supply their options.

So, `monorepo-lsp --eager` starts every backend it serves, meaning every
server type in every scope, as soon as the client sends `initialize`. It waits
for them to start before answering. The answer then announces every feature
those backends offer, with their options combined the same way as a
registration's, including their commands with the server-type prefixes
described below. Since the announcement has no document selector, it covers
every file, and the proxy answers for files with no backend offering the
feature as above. A feature announced in the answer is never registered later.
Semantic tokens are only announced if every backend numbers them with the same
legend, since the client decodes every file's tokens with the one legend it was
given. Otherwise the proxy leaves them to registration as usual.

The spec doesn't let a server send the client requests before it has answered
`initialize`. Anything the backends send the client while the proxy waits,
such as log messages or requests for configuration, is therefore held back
until the answer has gone.

The proxy waits up to 30 s. That is long enough for a server in a running
container to answer `initialize`, which the example's servers take about a
second to do. A hung backend still can't hold the client up indefinitely. A
backend that is still starting when the time runs out joins later, in the same
way as a lazily started one. Its features are registered if the answer didn't
already announce them. If they were announced, the client already sends their
requests to the proxy. However, the answer can't be widened afterwards. A
client like Neovim won't run any commands the late backend adds, unless the
Neovim module sends them on. The client won't use its extra trigger characters
either, and it decodes the late backend's semantic tokens with the legend it
was given. A backend that fails to start doesn't hold up the answer. The proxy
reports it once it has answered, as "Failures" below describes.

It's a flag, since whether it suits depends on the client more than the repo.
An agent wants the whole repo at once. An editor may well not want every
container's server started whenever it opens one file.

### Position encoding

Positions in LSP are a line and a character offset. By default the offset counts
UTF-16 code units, which is how JavaScript strings work. Characters outside the
Basic Multilingual Plane, such as most emoji, count as two. Since LSP 3.17,
clients can offer other encodings and the server picks one. Every server has to
support UTF-16.

The proxy chooses the encoding for the connection when it answers `initialize`,
before any backend has started. It then offers each backend only that encoding.
If the encodings differed, the proxy would have to convert every position in
every message, which needs the contents of every file. Since UTF-16 is the only
encoding every backend is guaranteed to accept, the proxy picks it.

### Open files

The proxy forwards `didOpen`, `didChange`, `didSave` and `didClose` only to the
backends that handle the file.

The proxy asks the client for the full contents of a file on every change. As I
read the spec, a change carrying the full contents is valid whatever kind of
sync a backend asked for, which spares the proxy from applying edits itself.
Messages are larger as a result. Applying incremental edits correctly means
converting UTF-16 offsets into the implementation language's own strings. That
can come later if message size turns out to matter.

A backend starts on the first `didOpen` for one of its files, which means no
other file of its can already be open. There is nothing to replay to it.

### Requests about one file

These go to the backends handling that file. Usually that's one.

When several backends handle the file, the proxy sends requests that want one
answer (such as hover, definition, rename and formatting) to one of them. It
picks the backend whose server type has the highest `priority`, among those
that offer the feature. A server type's priority is an integer and defaults
to 0. Ties go to the server type whose name sorts first. Config order would be
the obvious way to choose instead. However, TOML doesn't guarantee the order of
a table's keys, even though most parsers keep it.

```toml
[servers.ruby-lsp]
languages = ["ruby"]
priority = 10
```

Requests whose answers can be combined, such as completion and code actions,
go to all of them. The proxy concatenates the results.

The spec gives a code action no unique ID, only a title. Two servers may well
offer actions with the same title. When a response combines code actions from
more than one server, the proxy therefore prefixes each title with its server
type, as in `[rubocop] Autocorrect`.

Some requests follow up on an item a backend returned earlier, such as
`codeAction/resolve`, `completionItem/resolve`, `codeLens/resolve` and
`workspace/executeCommand`. They have to reach the backend that made the item.
The proxy wraps each item's `data` field with the backend it came from and
unwraps it on the way back. It prefixes command IDs with the server type, which
also keeps two servers' commands from colliding. A command doesn't say which
scope it came from, though. When more than one backend of that server type is
running, the proxy picks the one whose scope contains a file the command's
arguments refer to, and otherwise the first by scope.

### Requests about no particular file

Workspace symbol search, configuration changes and shutdown go to every running
backend. The proxy merges the answers. Changes to watched files go to the
backends whose scopes contain them.

The proxy announces workspace symbol search at startup, whether or not the
client could register it later. Since the search isn't about any one file,
there's nothing for a registration's document selector to narrow. Until a
backend has started, the proxy answers with no symbols. With `--eager`, every
backend has started (or failed) by the time the client can ask.

### Requests from backends

Servers send requests to the client too, such as `workspace/configuration` and
`workspace/applyEdit`. Since two backends can pick the same request ID, the
proxy gives each request its own ID on the way out and maps the reply back. It
does the same for cancellations and progress tokens.

### Diagnostics

Each `textDocument/publishDiagnostics` replaces whatever the client had for that
file. When two servers check the same file, forwarding both would leave only the
second server's diagnostics. Instead, the proxy keeps each backend's latest set
for each file and publishes their union. When a backend stops, the proxy
publishes the union again without it.

Clients can also pull a file's diagnostics with `textDocument/diagnostic`. The
proxy asks every backend for the file that offers it and concatenates their
items. A backend can give its report a result ID, which the client sends back
next time so the backend need only report what changed. One backend's ID means
nothing to another, so the proxy drops them. Every backend then reports in
full each time.

### Paths

Every message passes through the same path translation as the Neovim harness.
It walks every string and object key in the payload, rewriting `file://` URIs
wherever they appear (even inside hover markdown) and absolute paths when they
make up a whole string.

A fetched file's server path becomes the client path of its copy in a cache
directory on the host. However, bare strings such as `/` are common in
payloads, for example as completion trigger characters. Rewriting them would
break those payloads. So, the proxy only rewrites a fetched file's path when the
path is part of a URI.

Neovim lets a plugin fetch a file as its buffer opens. Most clients, including
VS Code and MCP bridges, give the proxy no hook like that. Instead, when a
backend sends back a location in a fetched file, the proxy runs `read` for it
_before_ forwarding the reply. The client can then open it like any other file.

The cache directory is `<root>/monorepo-lsp/<repo>/<server type>/<scope>`.
`<root>` is the system's temp directory unless the config's `cache` table
chooses another. Two backends can have
different files at the same server path. So, each server type and scope gets a
directory of its own. Each is percent-encoded into a single path segment, with
the root scope `.` as `%2E`. Nesting the scopes instead would put
`services/api`'s copy of `/x.rb` where `services` keeps its `/api/x.rb`. Since
two repos can also share a name, `<repo>` is the repo directory's name followed
by a dash and the CRC-32 of its full path in hex.

None of this depends on the backend ID. A restarted backend reuses the
directory of the one it replaced, rather than leaving a new one behind each
time. The backends of other proxies on the same repo use it too.

However, the temp directory isn't always stable. `nix develop` points `TMPDIR`
at a new directory for each shell. An editor started from one would then use a
new cache each time and leave the old ones behind. So, `cache` can follow the
XDG Base Directory spec, name an environment variable to read the root from,
or give the root as a path. A relative path is resolved against the config's
directory, which is the repo root. The layout under the root is the same
whichever way it's chosen. When the root is inside the repo,
`monorepo-lsp/<repo>` adds two levels of directories that aren't needed. That
being said, several checkouts of the repo can share an absolute root. `<repo>`
keeps their copies apart.

With `xdg = true`, the root is `$XDG_CACHE_HOME` if it's set to an absolute
path, and `$HOME/.cache` otherwise, as the spec says. Since macOS doesn't set
`XDG_CACHE_HOME`, a config that named it with `env` would fall back to the temp
directory there. `xdg` behaves the same on every OS, which suits a config the
whole team shares.

If the variable that `env` names is unset, empty or a relative path, or `xdg`
finds neither `XDG_CACHE_HOME` nor `HOME` usable, the proxy warns and falls
back to the temp directory. Since the cache only saves fetching files again,
refusing to serve over a missing variable would cost far more than it saves.
`~` isn't expanded. A path that starts with it is an error, since it would
otherwise make a directory named `~` in the repo.

Copies persist across restarts. However, the container's libraries may have
changed since an earlier backend fetched them. So, each backend fetches a file
again the first time it needs it, then reuses that copy until it stops. The
new copy is written beside the old one under a name of its own, then renamed
over it. A client reading the file, possibly through another proxy, sees
either the old copy or the new one. If the fetch fails, the old copy stays,
since a stale file is more use to the client than a location that leads
nowhere.

### Failures

If a backend can't start, for example because its service won't come up or its
command is missing, the proxy tells the user with `window/showMessage`. The
message names the server type, the scope and the reason.

When none of the backends that handle a file is running, because some have
failed and the rest never started, the proxy answers every request about the
file with `RequestFailed` (`-32803`). The error has the same message the user
was shown for each failed backend. An agent sees that error in its tool's
result, even if its bridge doesn't pass messages on. A command whose server
type's backends have all failed gets the same error. However, if any backend
for the file is running, a request that none of them can answer gets the usual
empty answer. Otherwise a file's failed linter would turn every request for a
feature that ruby-lsp doesn't offer into the linter's error.

If a running backend exits, the proxy reports it the same way, as a warning,
and withdraws its diagnostics. A backend that failed either way starts again
on the next `didOpen` for one of its files, but no sooner than 30 s after it
failed. The message says so. Files that were already open aren't replayed to
the new backend. That is the same with `--eager`. Only the first start is
eager, and a backend that failed then starts again on a `didOpen` like any
other.

A backend that keeps failing would otherwise show the user the same message
every 30 s. The proxy shows at most one message a minute for each server type
and scope, and logs the rest.

If the config is missing or invalid, the proxy still answers `initialize`, then
shows a message saying so, with the config's errors. It then serves nothing
until it restarts, answering every request with an empty result. Failing
`initialize` instead would leave many clients retrying the proxy or giving up
on it, with the reason only in a log.

A server type's boot table might set a `service` for the `compose` runner. A
scope that switches that server type to `host` then has a value that its
runner's templates never use. Since that is often deliberate, it isn't an
error. However, an unused value can also be a misspelt key, such as `arg` for
`args`. So, after answering `initialize`, the proxy shows one warning that
lists every unused value in every scope. It then serves as usual.
`languages` and `check` print the same warnings to stderr and still succeed.

## Clients

We're prototyping with Neovim and VS Code, plus an MCP bridge for agents. Other
clients aren't researched yet, but nothing in the design should rule them out.

**Neovim** can run one bare `monorepo-lsp` client for every filetype, or one per
server type with names. Once the proxy matches the harness, the harness can come
out of my dotfiles.

**VS Code** has no built-in way to launch an arbitrary language server.
Language extensions don't always let you replace their server command either.
Shopify's Ruby LSP extension doesn't[2]. A small companion extension, built on
`vscode-languageclient`, can run the bare command for every file. Anyone using
it would disable the extensions it replaces for the workspace.

Extensions that do let you replace the command can name their server type
instead. `vscode-rubocop` has a `rubocop.commandPath` setting, for example. I
haven't checked whether pointing it at the proxy works.

**Agents** can use an MCP bridge such as mcp-language-server[3], given
`--lsp monorepo-lsp` and the repo root as `--workspace`. I believe its
`definition` tool looks symbols up by name through workspace symbol search, but
I haven't confirmed it. If so, the bridge leans heavily on the proxy merging
that search across backends.

## Pinned for later

These may become worth doing once we've used the prototype.

- **Choosing which backends start eagerly.** `--eager` starts all of them.
  Some server types, or scopes, may be worth starting with the proxy even in an
  editor, while others start containers people may not want.
- **Starting stopped containers.** The Neovim harness runs `compose up
  --detach` when a service isn't running, then execs into it. With `prepare`,
  that is one line of config, such as
  `["{cfg:compose}", "up", "--detach", "{cfg:service}"]` or
  `["docker", "start", "{cfg:container}"]`. Not everyone will want it, which is
  why the built-in runners leave it out for now.
- **A `stop` template.** A runner whose `prepare` starts a container leaves it
  running after the backend exits. A `stop` template could run when a backend
  exits or the proxy shuts down. We'd need to decide whether it runs after a
  crash as well as a clean exit, and how long to wait for it. It can't help if
  the proxy itself is killed. Cleaning up after that would need a container
  name or label that a restarted proxy can work out again, which a random
  backend ID isn't.
- **Mount flags from `paths`.** `docker-run` could build its `--volume` flags
  from `paths` if the templates could expand a value once per entry. That is a
  feature of the template language, which is worth adding only if the
  repetition starts to bite.
- **Replaying open files to a restarted backend.** Since the client sends a
  file's full contents on every change, the proxy only needs to keep the latest
  one.
- **Incremental sync**, if full contents on every change turns out to be too
  slow.
- **Smarter merging** of answers from several servers.

[1]: https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#client_registerCapability
[2]: https://github.com/Shopify/ruby-lsp/discussions/3738
[3]: https://github.com/isaacphi/mcp-language-server
