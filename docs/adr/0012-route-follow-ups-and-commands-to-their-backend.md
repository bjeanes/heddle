# 0012. Route follow-ups and commands to their backend

Status: Accepted, 2026-10-25, revised 2026-10-26

## Context

Some requests name no file. A `*/resolve` request holds only the item a backend
returned earlier. `workspace/executeCommand` holds only a command ID and its
arguments. Two servers can pick the same command ID. Two backends of one server
type, in different scopes, offer the same commands. VS Code refuses a command ID
that is registered twice.

## Options considered

- **Mark each item's `data`** with the backend that made it, and unwrap it on
  the way back.
- **Prefix command IDs with the server type**, as in `rubocop:autocorrect`.
- **Prefix them with the slot**, naming the scope as well. A review of the
  codebase suggested this, so that a command says where it came from. The cost
  is that `--eager` announces every command once per scope.
- **Keep the server type prefix and register each command once.** In practice
  only one scope's command applies to the open file. The command's arguments
  nearly always name that file, which picks the scope.

## Decision

heddle marks items with their backend, including call and type hierarchy items
([`00b9c67`], [`daac91e`]). It prefixes commands with the server type only
([`8b1ac12`]). Commands a backend registers itself join heddle's own
registration, recorded against that backend ([`25e1e9c`]). Counting how many
backends registered each command wouldn't do, since a backend that crashes never
unregisters. When a backend stops, heddle withdraws what it registered
([`2a6a262`]). A command goes to a ready backend that offers it, preferring the
deepest scope containing a file named in its arguments ([`07ebe51`]). See
[Requests about one file].

## Consequences

A command whose arguments name no file goes to the first scope by name, which is
a guess. Clients that run a server's command by its well-known name won't find
it under the prefix. Items made by a backend that has since restarted can't be
resolved.

[`00b9c67`]: https://github.com/bjeanes/heddle/commit/00b9c67ad6bbe3e2894182cf8aa331bf60d1ee02
[`daac91e`]: https://github.com/bjeanes/heddle/commit/daac91e1e8cdee2463dbceaeb45766a868200a6c
[`8b1ac12`]: https://github.com/bjeanes/heddle/commit/8b1ac1254ad50e0fbb8cb9d0ae79a09c6601793e
[`25e1e9c`]: https://github.com/bjeanes/heddle/commit/25e1e9c427c4cf060530ab893b654bf4a372eef1
[`2a6a262`]: https://github.com/bjeanes/heddle/commit/2a6a262cb32944fb2a349021cc3a0beb501bffd9
[`07ebe51`]: https://github.com/bjeanes/heddle/commit/07ebe515938f23ebec4c26e2697e11e27e3553d1
[Requests about one file]: https://github.com/bjeanes/heddle/blob/07ebe515938f23ebec4c26e2697e11e27e3553d1/DESIGN.md#requests-about-one-file
