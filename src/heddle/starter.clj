(ns heddle.starter
  "The config that `heddle init` writes. Everything in it is commented out, so
  it loads as written, with no server types. Examples start with `#` and prose
  with `# `, which is how the tests tell them apart."
  (:require [clojure.string :as str]
            [heddle.runner :as runner]))

(defn- comment-out [lines]
  (str/join "\n" (for [line lines]
                   (if (str/blank? line) "" (str "#" line)))))

(def ^:private builtin-runners
  "The built-in runners' definitions, without the resource's own prose."
  (drop-while #(or (str/blank? %) (str/starts-with? % "#"))
              (str/split-lines runner/builtin-toml)))

(def toml
  (str "# heddle's config. Uncomment and change what you need, then run
# `heddle check` to see any problems. README's \"The config in full\" describes
# every key.

# A server type says which files a language server handles, and how to start it
# by default.
#[servers.ruby-lsp]
#languages = [\"ruby\"]                        # the language IDs your editor sends
#globs = [\"**/Gemfile\"]                      # and/or globs on paths from the repo root
#boot = { command = [\"bundle\", \"exec\", \"ruby-lsp\"] }

# A server type does nothing until a scope starts it. A scope starts server
# types for the files under a directory, relative to the repo root. When more
# than one scope starts a server type for a file, the deepest one wins. A
# scope's values go over the server type's boot table, and name the runner that
# starts the server. To try one, uncomment it along with the server type.

# Run it on your machine, for the whole repo.
#[scopes.\".\"]
#ruby-lsp = { runner = \"host\" }

# Run it in a running Compose service, which mounts services/api at /app.
#[scopes.\"services/api\"]
#ruby-lsp = { runner = \"compose\", service = \"api\", paths = { \"services/api\" = \"/app\" } }

# Run it in a running container, which mounts services/worker at /app.
#[scopes.\"services/worker\"]
#ruby-lsp = { runner = \"docker-exec\", container = \"worker\", paths = { \"services/worker\" = \"/app\" } }

# Start a new container for it, with the repo mounted at /src.
#[scopes.\"tools/cli\"]
#ruby-lsp = { runner = \"docker-run\", image = \"ruby:3.3\", args = [\"--volume\", \"{client:repo}:/src\"], paths = { \".\" = \"/src\" } }

# Where to keep copies of files that servers point you at, such as installed
# gems. Without this, they go in the system's temp directory.
#[cache]
#xdg = true                                  # or env = \"VAR\", or path = \"/absolute/dir\" or \"dir/in/the/repo\"

# These runners are built in. To change one, uncomment it here and edit it, or
# add a runner of your own under a new name.

" (comment-out builtin-runners) "\n"))
