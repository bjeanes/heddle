# 0003. Separate server types from scopes

Status: Accepted, 2026-10-25

## Context

When a file is opened, heddle has two questions to answer. First, it needs to
know which language servers should handle the file. We can tell that from the
file's language, or sometimes from its name. Second, for each of those servers,
it needs to know how to start one. That depends on where the file sits in the
repo. For example, Ruby files in one service might need ruby-lsp started inside
that service's container. Ruby files in another service need it started in a
different container, and a third service might run it on the host.

The first sketch of the config looked up a server's command by language alone.
That would only work if every service started each server the same way.

## Options considered

- **Commands keyed by language.** The config would give one command per
  language, and heddle would start a backend with it for each root directory.
  Since there's only one command, every root would start the server the same
  way. That can't describe a service whose server runs in a different container,
  or on the host.
- **Server types, with scopes as an ordered list of globs.** Each scope would
  match files with a glob, such as `services/api/**/*.rb`, and the first scope
  in the list that matches a file would handle it. However, a backend needs a
  root directory to start in, and a glob doesn't name one. heddle would have to
  take the part of the glob before its first wildcard (`services/api` here).
  That rule is hard to explain, and a glob like `**/api/*.rb` has no such part
  at all. The order of the list would also matter, which makes the config harder
  to read. We couldn't think of a use for scope globs beyond "the whole repo".
- **Server types, with scopes keyed by directory.** Each scope is a directory,
  which is also its backend's root. When several scopes contain a file, the
  longest directory wins.

## Decision

A server type matches files by `languages`, `globs` or both, and holds `boot`
defaults. A scope is a directory, with `.` for the repo root. For each server
type that matches a file, heddle uses the longest scope that contains the file
and starts that type. Each [server type, scope] pair gets one backend, which the
code later named a slot ([`4f13757`]). To run one backend per service, you list
each service as a scope. See [Configuration] ([`c5c2344`], [`a5b352e`]).

## Consequences

Scopes need no order. Since a nested directory is always longer than the one
containing it, there is never a tie. A catch-all has to be written out as a
scope at `.`. Since a file sits in one scope per server type, it reaches one
backend of each type. That leaves code shared between services, such as a path
gem, served by none of their backends ([KNOWN-ISSUES.md]).

[`4f13757`]: https://github.com/bjeanes/heddle/commit/4f137570d9ae8c76803f70e88b15e05ba88f91b8
[`c5c2344`]: https://github.com/bjeanes/heddle/commit/c5c2344b10b64b1ca5306bec7dbe79670979f44d
[`a5b352e`]: https://github.com/bjeanes/heddle/commit/a5b352e15522abcab0d1d99857bdb682fc132822
[Configuration]: https://github.com/bjeanes/heddle/blob/a5b352e15522abcab0d1d99857bdb682fc132822/DESIGN.md#configuration
[KNOWN-ISSUES.md]: https://github.com/bjeanes/heddle/blob/9e75a1e64c74c4825229d864ac7c38c9dcde983d/KNOWN-ISSUES.md#paths-and-fetched-files
