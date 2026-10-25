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

So, the proxy can be run either way. Running `monorepo-lsp` on its own serves
every server type in the config. Naming server types, as in
`monorepo-lsp ruby-lsp` or `monorepo-lsp ruby-lsp rubocop`, serves only those.
Both are the same code. The names only filter which server types the proxy will
start.

The proxy finds its config by looking for `.monorepo-lsp.toml` in the workspace
root the client sends at startup, falling back to the directory it was started
in. The file name will likely change along with the project's name.

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

These are the runners proposed to ship with the proxy:

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
as each backend starts, the proxy registers that backend's features with the
client (`client/registerCapability`[1]), limited to that backend's files with a
document selector.

Clients say, feature by feature, whether they accept registrations after
startup. For a feature the client can't register later, the proxy announces it
at startup and answers with an empty result whenever no backend for the file
offers it.

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
answer (such as hover, definition, rename and formatting) to one of them. Which
one is an open question below. Requests whose answers can be combined, such as
completion and code actions, go to all of them. The proxy concatenates the
results.

The spec gives a code action no unique ID, only a title. Two servers may well
offer actions with the same title. When a response combines code actions from
more than one server, the proxy therefore prefixes each title with its server
type, as in `[rubocop] Autocorrect`.

Some requests follow up on an item a backend returned earlier, such as
`codeAction/resolve`, `completionItem/resolve`, `codeLens/resolve` and
`workspace/executeCommand`. They have to reach the backend that made the item.
The proxy wraps each item's `data` field with the backend it came from and
unwraps it on the way back. It prefixes command IDs with the server type, which
also keeps two servers' commands from colliding.

### Requests about no particular file

Workspace symbol search, configuration changes and shutdown go to every running
backend. The proxy merges the answers. Changes to watched files go to the
backends whose scopes contain them.

### Requests from backends

Servers send requests to the client too, such as `workspace/configuration` and
`workspace/applyEdit`. Since two backends can pick the same request ID, the
proxy gives each request its own ID on the way out and maps the reply back. It
does the same for cancellations and progress tokens.

### Diagnostics

Each `textDocument/publishDiagnostics` replaces whatever the client had for that
file. When two servers check the same file, forwarding both would leave only the
second server's diagnostics. Instead, the proxy keeps each backend's latest set
for each file and publishes their union.

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

### Failures

If a backend can't start, for example because its service won't come up or its
command is missing, the proxy tells the user with `window/showMessage`. It also
answers any request for a file that backend would have handled with an error
explaining why. An agent sees that error in its tool's result, even if its
bridge doesn't pass messages on.

If a backend exits, the proxy reports it the same way and starts it again on
the next `didOpen` for one of its files. Files that were already open aren't
replayed to the new backend.

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

- **Starting backends eagerly.** With the bare command, some server types could
  start with the proxy. Workspace symbol search would then cover services nobody
  has opened a file in yet. However, it would also start containers people may
  not want. A slow or failing start would hold up everything else too.
- **Starting stopped containers.** The Neovim harness runs `compose up
  --detach` when a service isn't running, then execs into it. With `prepare`,
  that is one line of config, such as
  `["{cfg:compose}", "up", "--detach", "{cfg:service}"]` or
  `["docker", "start", "{cfg:container}"]`. Not everyone will want it, which is
  why the built-in runners leave it out for now.
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

## Open questions

- When several backends handle a file, which one answers a request that wants
  one answer? Config order is the obvious choice. However, TOML doesn't
  guarantee the order of a table's keys, even though most parsers keep it. An
  explicit `priority` on server types would avoid relying on that.

[1]: https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#client_registerCapability
[2]: https://github.com/Shopify/ruby-lsp/discussions/3738
[3]: https://github.com/isaacphi/mcp-language-server
