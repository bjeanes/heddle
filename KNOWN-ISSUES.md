# Known issues and limitations

This is an early prototype. These are the gaps we know about, grouped by where
you'd notice them. [DESIGN.md](DESIGN.md) has the reasoning behind several of
them, under "Pinned for later".

## Language features

- **Some requests are answered by only one server.** When several servers
  handle a file, hover, definition, rename, formatting and the like go to the
  server type with the highest `priority`. Completion, code actions, references
  and a few others are combined from all of them, without any smarter merging.
- **Semantic tokens from several servers can be miscoloured.** The client
  decodes every file's tokens with one legend. When servers number their tokens
  differently, only one server's legend is used.
- **Semantic tokens and on-type formatting need `--eager` in some clients.**
  Without it, the proxy can only offer them to clients that accept
  registrations after startup.
- **A server that registers a feature itself is registered once for each
  scope it runs in.** Clients such as VS Code may then ask the proxy, and show
  the answer, once per registration.
- **Pulled diagnostics are always reported in full**, since the proxy drops
  each server's result IDs.
- **The client sends whole files on every change.** Messages are larger than
  with incremental sync.
- **File operations and workspace folder changes aren't passed on.** Servers
  aren't told about files you rename, create or delete through the editor, or
  about folders added to the workspace.
- **No formatters.** Formatters that aren't language servers, such as
  Prettier, can't be run through the proxy yet.
- **Paths in some unusual forms aren't translated.** A `file://` URI whose path
  contains an unencoded `(` or `'` is cut short there. A bare path containing a
  space isn't translated at all.

## Starting, stopping and failures

- **Without `--eager`, a server starts when you first open one of its files.**
  Until then, workspace symbol search and references don't cover it, and
  clients that only use what a server announces at startup won't use its
  features. `--eager` starts every server; there's no way to choose some.
- **A server that failed starts again only when you open one of its files**,
  and no sooner than 30 s after it failed.
- **With `--eager`, a server that takes longer than 30 s can't add to what was
  announced.** The client won't run its extra commands (except through the
  Neovim module), use its extra trigger characters, or use its semantic token
  legend.
- **Containers started by a runner's `prepare` keep running.** There's no
  teardown step yet, so you'll need to stop them yourself.
- **A server that ignores `exit` is left running** once the proxy has waited
  2 s for it.
- **The config is read once.** After editing it, restart the proxy (or your
  editor's client).

## Paths and fetched files

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
  macOS, from Neovim 0.12.

## Building and platforms

- **Nothing has been published as a release yet.** For now, you build it
  yourself with the Nix dev shell, which provides Jolt for aarch64 macOS and
  x86_64 Linux only.
- **Linux binaries need glibc 2.28 or newer**, which rules out musl-based
  systems such as Alpine.
- **Windows hosts and remote Docker contexts aren't supported.**
- **Some TOML mistakes are reported poorly.** Invalid TOML is reported only by
  line and column. A table declared twice replaces the first without an error.
- **Jolt can't fetch Maven dependencies behind an HTTP proxy**, since its
  resolver ignores `HTTPS_PROXY`.
