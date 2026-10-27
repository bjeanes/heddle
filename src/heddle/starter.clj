(ns heddle.starter
  "The config that `heddle init` writes. Since everything in it is commented
  out, it loads as written, with no server types. Examples start with `# ` and
  prose with `## `, which is how the tests tell them apart.")

(def toml
  "## heddle starts the right language servers for each part of this repo, and
## gives your editor a single server to talk to.
##
## Lines that start with `# ` are examples. To use one, delete the `# `. Lines
## that start with `## ` are notes, which stay commented out even if you
## uncomment the whole file at once.
##
## To get going:
##   1. Uncomment a server, or write your own.
##   2. If it should run somewhere other than your machine, uncomment a
##      \"where to run it\" example, and adjust it.
##   3. Run `heddle check`, then point your editor at `heddle`.
##
## README's \"The config in full\" describes every key.

## 1. Servers ------------------------------------------------------------------
##
## Name each language server, say which files it handles and how to start it.
## Your editor sends a language ID with each file it opens. To match files by
## path as well, or instead, give `globs` from the repo root, such as
## \"**/*.ts\". `boot` says how to start the server, wherever it runs. With
## nothing else, heddle runs that command on your machine, for the whole repo.

# [servers.ts-ls]                              # named as you like
# languages = [\"typescript\", \"typescriptreact\", \"javascript\", \"javascriptreact\"]
# boot = { command = [\"typescript-language-server\", \"--stdio\"] }

## 2. Where to run it (optional) -----------------------------------------------
##
## A scope says how to run a server for the files under one directory, relative
## to the repo root (\".\" means the whole repo). For each server it starts, it
## can name a runner, which is how heddle starts the server there. Without one,
## heddle runs the server's command directly, on your machine. If two scopes
## cover a file, the deeper one wins.
##
## heddle lays a scope's keys for a server over that server's `boot`, key by
## key. That lets you give the command once, above, and only say where it runs
## here.

## In a Compose service that's already running. `paths` says where the
## container sees your files. Here, the `web` service mounts apps/web at /app.
## heddle uses `paths` to translate file paths between your editor and the
## server. The runner after it starts the server with `docker compose exec`.
# [scopes.\"apps/web\".ts-ls]
# runner = \"compose\"
# service = \"web\"
# paths = { \"apps/web\" = \"/app\" }
#
# [runners.compose]
# defaults = { compose = [\"docker\", \"compose\"] }
# start = [\"{cfg:compose}\", \"exec\", \"-T\", \"--workdir\", \"{server:scope}\", \"{cfg:service}\", \"{cfg:command}\"]
# read = [\"{cfg:compose}\", \"exec\", \"-T\", \"{cfg:service}\", \"cat\", \"{read:file}\"]

## Once a scope names a server, it runs only where scopes name it, rather than
## across the whole repo. To keep it on your machine for the rest of the repo,
## name it at \".\" as well. `heddle check` lists where each server runs.
# [scopes.\".\".ts-ls]

## 3. Runners (optional) -------------------------------------------------------
##
## heddle has no runners of its own. To run a server anywhere other than your
## machine, such as in Docker, in `nix develop` or over `ssh`, write a runner
## for it, as the Compose example above does. README's \"Example runners\" has
## more to copy, for `docker exec` and `docker run`. \"Runners\" covers them in
## full.
##
## A runner is a few commands, each a list of arguments that heddle runs
## without a shell. `start` runs the server, which then speaks LSP over its
## stdin and stdout. If there's a `prepare`, heddle runs it to completion first.
## `read` prints a file from the server's side. heddle uses it to copy out files
## that `paths` doesn't cover, such as installed packages, for you to open.
##
## The commands fill in placeholders:
##   {cfg:NAME}          NAME from the scope or `boot`, or else from `defaults`
##   {client:repo}       the repo, as a path on your machine
##   {client:scope}      the scope's directory, as a path on your machine
##   {server:scope}      the scope's directory, as the server sees it
##   {proxy:backend_id}  an ID for each server heddle starts
##   {read:file}         the file for `read` to print
##
## This runner starts a container, then runs the server inside it. Its default
## `args` mount the repo at /src in the container. Its default `paths` tell
## heddle that's where the repo is. Every scope that uses the runner gets both,
## leaving the one after it to name only an image. Since heddle has no teardown
## step yet, the container keeps running after the server exits, until you
## `docker stop` it.

# [runners.docker-detached]                    # named as you like
# defaults = { args = [\"--volume\", \"{client:repo}:/src\"], paths = { \".\" = \"/src\" } }
# prepare = [\"docker\", \"run\", \"--rm\", \"--detach\", \"--name\", \"{proxy:backend_id}\", \"{cfg:args}\", \"{cfg:image}\", \"sleep\", \"infinity\"]
# start = [\"docker\", \"exec\", \"--interactive\", \"--workdir\", \"{server:scope}\", \"{proxy:backend_id}\", \"{cfg:command}\"]
# read = [\"docker\", \"exec\", \"{proxy:backend_id}\", \"cat\", \"{read:file}\"]
#
# [scopes.\"tools/cli\".ts-ls]
# runner = \"docker-detached\"
# image = \"my-app-dev\"                         # an image with the server installed

## 4. The cache (optional) -----------------------------------------------------
##
## When a server points you at a file that only exists on its side, heddle
## copies it out for you. The copies go in the system's temp directory, unless
## you say otherwise here. `xdg = true` keeps them in ~/.cache, which stays put
## across shells that change the temp directory, such as `nix develop`.

# [cache]
# xdg = true                                   # or env = \"VAR\", or path = \"/absolute/dir\" or \"dir/beside/this/file\"

## 5. Tracing (optional) -------------------------------------------------------
##
## To see every message between your editor, heddle and the servers, name a
## file to append them to. `heddle serve --trace FILE` overrides this.

# [trace]
# path = \"heddle-trace.jsonl\"                  # relative to this file, or absolute
")
