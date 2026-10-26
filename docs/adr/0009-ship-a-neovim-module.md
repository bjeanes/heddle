# 0009. Ship a Neovim module

Status: Accepted, 2026-10-26

## Context

Neovim starts its own language servers for a file's language, alongside heddle.
Those duplicate heddle's work, usually without the dependencies they need. The
first README snippet kept them away by reconfiguring `ruby_lsp` and `rubocop`
globally, which meant changing every project for the sake of one. It also only
worked because their names happened to match the server types. Separately,
Neovim 0.12 only runs commands listed in the `initialize` answer, which heddle
doesn't yet know at the time it answers ([`fcd8d9b`]).

## Options considered

- **A `.nvim.lua` with `exrc`**, disabling the servers in the repo. Neovim loads
  it based on the directory it starts in. Disabling a server then applies to the
  whole session.
- **Match Neovim's configs by name** to heddle's server types. That only works
  by coincidence, such as `typescript-language-server` against `ts_ls`.
- **Match by language**, asking heddle which languages the config covers, since
  Neovim has no TOML parser.
- **Wrap each config's `root_dir`**, which misses configs enabled later.

## Decision

`lua/heddle.lua` is a module whose `setup()` wraps `vim.lsp.start`. It returns
nil for a buffer whose filetype the repo's config covers, asking
`heddle languages` once per repo ([`e50e8de`], [`b864e59`]). It's the one point
every start passes through, including servers that plugins start directly. The
client also gets a fallback that sends any command Neovim doesn't know to heddle
([`fcd8d9b`]). See [Neovim].

## Consequences

The wrapper relies on `vim.lsp.enable` looking up `vim.lsp.start` through the
table each time, which is true in 0.12.6. The smoke check would catch a change.
Since `heddle languages` answers for the whole repo, a file outside every scope
for its language gets no host server either. The module is packaged as a flake
output ([`9e404ec`]).

[`fcd8d9b`]: https://github.com/bjeanes/heddle/commit/fcd8d9b1d0593a656931d217c0afee1c50dbc23e
[`e50e8de`]: https://github.com/bjeanes/heddle/commit/e50e8deb5644b5630e5bb32adaa00153aa94a1cf
[`b864e59`]: https://github.com/bjeanes/heddle/commit/b864e59a2c5561beb36749faf6308f96f74502b1
[`9e404ec`]: https://github.com/bjeanes/heddle/commit/9e404ecd9bf3274879146cbd013933df43fe337d
[Neovim]: https://github.com/bjeanes/heddle/blob/b864e59a2c5561beb36749faf6308f96f74502b1/README.md#neovim
