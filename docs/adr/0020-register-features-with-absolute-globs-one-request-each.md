# 0020. Register features with absolute globs, one request each

Status: Accepted, 2026-10-29. Amends
[0005](0005-answer-initialize-and-register-features-later.md).

## Context

heddle registers each backend's features with the client once the backend starts
([0005](0005-answer-initialize-and-register-features-later.md)). Each
registration has a document selector that limits it to the backend's files.
Those are files of its languages under its scope, `also` and cache directories,
and files matching its globs. heddle wrote each selector's pattern as an LSP
relative pattern, such as `{"baseUri": "file:///repo/api", "pattern": "**/*"}`.
It sent every registration for a backend in one `client/registerCapability`
request.

When I tried heddle in VS Code, through the Simple LSP Client extension, none of
its features worked. That extension bundles vscode-languageclient 9.x, which
passes a selector's pattern to VS Code unconverted[1]. Since `baseUri` stays a
string, VS Code doesn't recognise the pattern as a relative one. It then throws
when it matches a document against the selector. The library refuses the whole
request when that happens, which lost heddle every feature in that client.
vscode-languageclient 10.0.0 converts the pattern properly[2]. However, heddle
can't rely on every client having upgraded.

Neovim 0.12 only matches a pattern that is a string, and ignores a relative
one[3]. heddle's filters for a backend's `globs` name no language. Once Neovim
dropped their pattern, nothing was left to restrict them. They matched every
file. I believe Neovim then asked heddle for those features on files no backend
offered them for, which heddle answered with nothing.

## Options considered

- **Keep relative patterns**, and wait for clients to move to
  vscode-languageclient 10.
- **Absolute string globs**, such as `/repo/api/**/*`. The spec allows a string
  pattern without any client capability. The directory part has to match
  literally. However, VS Code's glob syntax has no escape character.
- **One request for all of a backend's registrations, or one for each.**
  ruby-lsp sends each of its own registrations in a request of its own[4].

## Decision

heddle registers absolute string globs. In the directory part, it writes `*`,
`?`, `[` and `\` as a bracket expression with one character, such as `[*]`,
which both VS Code and Neovim read literally. Since the two share no form for
`{`, `}` or `]`, heddle writes `?` for those. Each of heddle's own registrations
and unregistrations goes in a request of its own. If the client refuses a
registration, heddle stops counting it as registered. It sends it again the next
time it registers features, such as when a backend starts or stops.
Registrations that a backend sends itself keep the grouping the backend chose.

## Consequences

In Neovim, and in VS Code through Simple LSP Client, each feature is now limited
to the backend's files. VS Code accepted every registration. A client that
refuses one registration loses only that feature. A client that always refuses
it receives it again each time heddle registers features. heddle logs a line for
each refusal. If a directory's name contains `{`, `}` or `]`, its glob also
matches a sibling whose name differs only at that character. When a backend
registers a feature itself with a relative pattern, heddle passes that pattern
through as it is. Clients on vscode-languageclient 9.x can still refuse such a
registration. Registering takes a request per feature where it took one per
backend start.

[1]: https://unpkg.com/vscode-languageclient@9.0.1/lib/common/protocolConverter.js
[2]: https://unpkg.com/vscode-languageclient@10.0.0/lib/common/protocolConverter.js
[3]: https://github.com/neovim/neovim/blob/v0.12.6/runtime/lua/vim/lsp/client.lua
[4]: https://github.com/Shopify/ruby-lsp/blob/fb2e79425f686dba0aecd9741e72fab34bf170a6/lib/ruby_lsp/utils.rb#L152-L177
