# 0004. Define runners as command templates

Status: Accepted, 2026-10-25. The built-in runners were dropped under
[0019](0019-expand-the-config-when-it-loads.md).

## Context

heddle has to support Docker and Compose without assuming either. A server might
also run on the host, through `nix develop`, Podman or `kubectl exec`.

## Options considered

- **Runners built into the code.** These were `host`, `docker` and `compose`,
  plus a generic `wrap` that took a command prefix, declared paths and a read
  command.
- **Runners defined in config** as command templates, with the values they need
  supplied by the scopes that use them. A `wrapper` prefix became `start`, the
  whole command, with the server's command as just another placeholder.

## Decision

Runners are defined in config. A runner is a `start` template, with optional
`read` and `prepare`. Templates are lists of arguments and never go through a
shell. A list value splices in when it makes up a whole argument, and an empty
list removes it. For example, this is the `compose` runner, and a scope that
uses it:

```toml
[runners.compose]
defaults = { compose = ["docker", "compose"] }
start = ["{cfg:compose}", "exec", "-T", "--workdir", "{server:scope}", "{cfg:service}", "{cfg:command}"]
read = ["{cfg:compose}", "exec", "-T", "{cfg:service}", "cat", "{read:file}"]

[scopes."services/api"]
ruby-lsp = { runner = "compose", service = "api", command = ["bundle", "exec", "ruby-lsp"], paths = { "services/api" = "/app" } }
```

For a file under `services/api`, heddle runs
`docker compose exec -T --workdir /app api bundle exec ruby-lsp`.

A container usually sees the repo's files at a different path from the host. A
scope's `paths` says where, mapping directories in the repo to where the server
finds them. Here, `services/api` on the host is `/app` in the container.

A placeholder's prefix says what kind of value it is:

- `{cfg:…}` is a value from the config. `{cfg:service}` is the `service` set by
  the scope, the server type's `boot` or the runner's `defaults`, in that order.
- `{client:…}` and `{server:…}` are paths, as the client and the server see
  them. These are the LSP spec's names for the two sides that heddle translates
  between. `{server:scope}` is the scope's directory, after applying `paths`.
  That's `/app` in the example. `{client:repo}` is the repo root on the host.
- `{read:file}` is the file to print. It only exists while heddle runs `read`
  for that file, so using it in `start` is an error.
- `{proxy:…}` is anything else heddle provides, such as `{proxy:backend_id}`.
  `docker-run` names its container with it.

The `{root}` we started with was ambiguous between the repo and the scope. It
also didn't say which side's path it was.

heddle only provides values that exist for every valid scope. That rules out
`{server:repo}`, since a scope's `paths` may map only a subdirectory, as in the
example. That leaves the repo root with no path on the server's side. If a
template uses a `{cfg:…}` value that a scope doesn't supply, heddle reports it
as an error when it loads the config. `docker-exec` takes a container and
`docker-run` an image, since neither can be worked out from the other. Four
runners (`host`, `compose`, `docker-exec` and `docker-run`) shipped as plain
TOML that a project could override, so that configs stay short and pick up
fixes. See [Runners] ([`c5c2344`], [`696598f`]).

## Consequences

heddle itself knows nothing about Docker. Wrappers that need the server's
command as one quoted string, such as ssh, aren't supported yet. `docker-run`
lists its mounts twice, in `args` and `paths`. Nothing stops a container that
`prepare` started.

[`c5c2344`]: https://github.com/bjeanes/heddle/commit/c5c2344b10b64b1ca5306bec7dbe79670979f44d
[`696598f`]: https://github.com/bjeanes/heddle/commit/696598f486025e7acfe9f38464ccad83c80b68e5
[Runners]: https://github.com/bjeanes/heddle/blob/696598f486025e7acfe9f38464ccad83c80b68e5/DESIGN.md#runners
