# 0015. Reach Claude Code through an LSP plugin

Status: Accepted, 2026-10-26

## Context

Claude Code can use language servers, for its `LSP` tool and for the diagnostics
it checks after each edit. However, it only starts them through plugins. For
Claude to work with heddle, something has to tell Claude Code to start heddle,
and for which files.

## Options considered

- **An MCP bridge** such as mcp-language-server, registered as the MCP server.
  Nobody has tried one with heddle yet.
- **A plugin per project.** Only the map of file extensions is project-specific,
  and mapping extensions a project doesn't use does no harm.
- **Loading a plugin from the repo with `--plugin-dir`**, which an
  organisation's managed settings can disable.
- **A local marketplace** in the heddle repo, listing one plugin.

## Decision

The heddle repo is a marketplace with one plugin, under `claude-code/`
([`82728e2`]). It has a directory of its own because installing a plugin copies
its whole directory. The plugin runs `heddle serve --eager` for about 40 common
extensions. Claude Code gives each extension to the first plugin that registers
it. A plugin enabled everywhere would therefore take files from other servers
and serve nothing. So, you install it once, then enable it only in repos with a
`.heddle.toml`. See [Claude Code].

## Consequences

Claude Code's `LSP` tool and its diagnostics after each edit work through
heddle. The cache has to sit outside the repo, and Claude needs settings to read
it ([`c58fdc7`]). The plugin directory requires a LICENSE, which the repo
doesn't have yet.

[`82728e2`]: https://github.com/bjeanes/heddle/commit/82728e2d3e92fc41bc20b5465ebd2645a76968a5
[`c58fdc7`]: https://github.com/bjeanes/heddle/commit/c58fdc7bee0d7e44411908772ce41a4ed2e97ae0
[Claude Code]: https://github.com/bjeanes/heddle/blob/82728e2d3e92fc41bc20b5465ebd2645a76968a5/README.md#claude-code
