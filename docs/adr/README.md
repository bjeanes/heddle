# Architecture decision records

These record the broad decisions made while building heddle. Each one says what
the problem was, what was tried or considered, what was decided and what
followed from it. [DESIGN.md](../../DESIGN.md) describes how heddle works.
These say why it works that way, and what else was on the table. They were
written after the fact, from the commit history, the design docs and the notes
kept while building it. Where those don't say why something was decided, the
record says so instead of guessing.

| Number | Title | Status |
| ------ | ----- | ------ |
| [0001](0001-write-heddle-in-jolt.md) | Write heddle in Jolt | Accepted |
| [0002](0002-build-a-standalone-proxy.md) | Build a standalone proxy | Accepted |
| [0003](0003-separate-server-types-from-scopes.md) | Separate server types from scopes | Accepted |
| [0004](0004-define-runners-as-command-templates.md) | Define runners as command templates | Accepted |
| [0005](0005-answer-initialize-and-register-features-later.md) | Answer `initialize` itself, and register features later | Accepted; amended by 0006 |
| [0006](0006-start-backends-lazily-with-eager-as-a-flag.md) | Start backends lazily, with `--eager` as a flag | Accepted |
| [0007](0007-declare-paths-and-fetch-the-rest.md) | Declare paths, and fetch everything else | Accepted |
| [0008](0008-make-the-first-word-a-subcommand.md) | Make the first word a subcommand, defaulting to `serve` | Accepted |
| [0009](0009-ship-a-neovim-module.md) | Ship a Neovim module | Accepted |
| [0010](0010-name-the-project-heddle.md) | Name the project heddle | Accepted |
| [0011](0011-report-failures-and-retry-after-30-s.md) | Report failures, and retry no sooner than 30 s | Accepted |
| [0012](0012-route-follow-ups-and-commands-to-their-backend.md) | Route follow-ups and commands to their backend | Accepted |
| [0013](0013-fail-initialize-only-without-a-config.md) | Fail `initialize` only when there's no config | Accepted |
