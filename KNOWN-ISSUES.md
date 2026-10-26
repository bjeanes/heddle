# Known issues and limitations

This is an early prototype. These are the gaps we know about, grouped by where
you'd notice them. [DESIGN.md](DESIGN.md) has the reasoning behind several of
them, under "Pinned for later".

## Language features

- **Some requests are answered by only one server.** When several servers
  handle a file, hover, definition, rename, formatting and the like go to the
  server type with the highest `priority`. Completion, code actions, references
  and a few others are combined from all of them. Beyond dropping duplicate
  references and workspace symbols, and labelling code actions with their
  server type, the proxy only concatenates them.
- **Semantic tokens from several servers can be miscoloured.** The client
  decodes every file's tokens with one legend. When servers number their tokens
  differently, only one server's legend is used.
- **Semantic tokens and on-type formatting need `--eager` in some clients.**
  Without it, the proxy can only offer them to clients that accept
  registrations after startup.
- **A feature that a server registers itself is registered once for each
  scope it runs in.** Clients such as VS Code may then ask the proxy, and show
  the answer, once per registration. Commands are the exception. The proxy
  merges those into a registration of its own, which lists each command once.
- **Pulled diagnostics are always reported in full**, since the proxy drops
  each server's result IDs.
- **The client sends the whole file on every change.** The proxy asks for it
  that way, to keep each open file's text for servers that start later. A
  server that syncs incrementally gets each change as one edit replacing the
  whole text. Messages are larger than with incremental sync.
- **File operations and workspace folder changes aren't passed on.** Servers
  aren't told about files you rename, create or delete through the editor, or
  about folders added to the workspace.
- **No formatters.** Formatters that aren't language servers, such as
  Prettier, can't be run through the proxy yet.
- **A server may answer with nothing until it has indexed.** Some servers,
  such as ruby-lsp, answer hover and definition with an empty result while
  they index after starting. The proxy passes that answer on, since it can't
  tell "not ready yet" from "nothing to say". Your editor shows the server's
  indexing progress. Once that finishes, try again.
- **Paths in some unusual forms aren't translated.** A `file://` URI whose path
  contains an unencoded `(` or `'` is cut short there. A bare path containing a
  space isn't translated at all.

## Starting, stopping and failures

- **Without `--eager`, a server starts when you first open one of its files.**
  Until then, workspace symbol search and references don't cover it, and
  clients that only use what a server announces at startup won't use its
  features. `--eager` starts every server; there's no way to choose some.
- **While a server starts, combined answers leave it out.** Completion, code
  actions, references and the like come only from the servers that have
  started, unless none has. A server whose `prepare` pulls an image can take a
  couple of minutes. Ask again once it has started to include its results.
- **A server that failed starts again only when you open one of its files**,
  and no sooner than 30 s after it failed.
- **With `--eager`, a server that takes longer than 30 s can't add to what was
  announced.** The client won't run its extra commands (except through the
  Neovim module), use its extra trigger characters, or use its semantic token
  legend.
- **Containers started by a runner's `prepare` keep running.** There's no
  teardown step yet, so you'll need to stop them yourself.
- **A server still running its `prepare` command when the proxy exits isn't
  stopped.** The proxy tells servers to exit over their connection. This one
  has none yet. Whatever `prepare` was starting keeps going. A `stop`
  template, pinned in [DESIGN.md](DESIGN.md), would cover this too.
- **The config is read once.** After editing it, restart the proxy (or your
  editor's client).

## Paths and fetched files

- **Code that several scopes share isn't served by their servers.** In a
  monorepo, several services may use a directory such as `shared/some_gem`,
  for example as a path gem in their Gemfiles. Since each service's server
  indexes it, you can jump _into_ it. However, a file there lies outside every
  service's scope. Once you open it, none of their servers serve it. Only a
  scope that contains it, such as `"."`, can start one for it. Even then,
  heddle sends a file to only one backend of each server type. Finding a
  shared file's references would need every service's server. This is a gap
  in the current approach to config, which I plan to address.
- **Fetched copies are read-only.** Edits need the real file, under an entry in
  `paths`.
- **Nothing cleans up fetched copies.** By default they live under the
  system's temp directory. Tools such as `nix develop` give each shell a new
  one, which leaves an old cache behind each time. The `cache` table can keep
  them somewhere stable, such as `xdg = true`, but nothing sweeps that either.
  See [The cache](README.md#the-cache).
- **`docker-run` needs its mounts listed twice**, once in `args` and again in
  `paths`.

## Editors and agents

- **There's no VS Code extension yet.**
- **Neovim needs the module to run servers' commands**, unless you use
  `--eager`. Neovim only runs commands announced at startup.
- **Nobody has tried an MCP bridge with it yet.**
- **It has only been tried with ruby-lsp and RuboCop**, in Docker Compose on
  macOS, from Neovim 0.12 and from Claude Code's `LSP` tool through the
  plugin.

## Building and platforms

- **There's no tagged release yet**, only the `edge` prerelease, built from
  every push to `main`.
- **The macOS binary isn't signed or notarised.** macOS refuses to run it if a
  browser downloaded it. See [Installing it](README.md#installing-it).
- **Building it yourself needs the Nix dev shell**, which provides Jolt for
  aarch64 macOS and x86_64 Linux only.
- **Linux binaries need glibc 2.28 or newer**, which rules out musl-based
  systems such as Alpine.
- **Windows hosts and remote Docker contexts aren't supported.**
- **Some TOML mistakes are reported poorly.** Invalid TOML is reported only by
  line and column. A table declared twice replaces the first without an error.
- **Jolt can't fetch Maven dependencies behind an HTTP proxy**, since its
  resolver ignores `HTTPS_PROXY`.
