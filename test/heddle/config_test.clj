;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.config-test
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [heddle.config :as config]
            [heddle.spec :as spec]))

(def design (config/read-config "test/heddle/fixtures/design"))

(def ^:private compose-and-host "
[runners.compose]
defaults = { compose = ['docker', 'compose'] }
start = ['{cfg:compose}', 'exec', '-T', '--workdir', '{server:scope}', '{cfg:service}', '{cfg:command}']
read = ['{cfg:compose}', 'exec', '-T', '{cfg:service}', 'cat', '{read:file}']

[runners.host]
start = ['{cfg:command}']
")

(defn- errors [toml]
  (try
    (config/parse toml)
    nil
    (catch clojure.lang.ExceptionInfo e
      (set (:errors (ex-data e))))))

(deftest server-types
  (is (= #{"ruby-lsp" "rubocop"} (config/server-types design "services/api/app/models/user.rb" "ruby")))
  (is (= #{"ruby-lsp"} (config/server-types design "services/api/Gemfile" nil)) "by glob alone")
  (is (= #{"ruby-lsp"} (config/server-types design "lib/tasks/db.rake" "plaintext")))
  (is (= #{"typescript"} (config/server-types design "services/web/app.tsx" "typescriptreact")))
  (is (= #{} (config/server-types design "README.md" "markdown"))))

(deftest languages
  (is (= ["ruby" "typescript" "typescriptreact"] (config/languages design)))
  (is (= ["ruby" "typescript" "typescriptreact"] (config/languages design [])))
  (is (= ["typescript" "typescriptreact"] (config/languages design ["typescript"])) "of the named server types")
  (is (= [] (config/languages design ["unknown"])))
  (is (= ["x" "y"] (config/languages (config/parse "
[servers.named]
languages = ['x']
boot = { command = 's' }
[servers.unnamed]
languages = ['y']
boot = { command = 's' }
[servers.by-glob]
globs = ['*.z']
boot = { command = 's' }

[scopes.'.'.named]
[scopes.'.'.by-glob]
")))
      "of server types whether or not a scope names them, and only their languages"))

(deftest expands-scopes
  (let [c (config/parse (str "
[servers.ruby-lsp]
languages = ['ruby']
boot = { runner = 'compose', service = 'app', command = 'ruby-lsp' }

[runners.r]
defaults = { runner = 'host', paths = { '.' = '/src' }, x = 'd', y = 'd' }
start = ['{cfg:x}', '{cfg:y}']

[scopes.'services/api'.ruby-lsp]
service = 'api'
[scopes.'tools/cli'.ruby-lsp]
runner = 'host'
compose = 'podman-compose'
[scopes.other.ruby-lsp]
runner = 'r'
y = 's'
" compose-and-host))
        entry #(get-in c ["scopes" % "ruby-lsp"])]
    (testing "into each slot's whole boot config"
      (is (= {"runner" "compose" "service" "api" "command" "ruby-lsp" "compose" ["docker" "compose"]}
             (entry "services/api")))
      (is (= {"runner" "host" "service" "app" "command" "ruby-lsp" "compose" "podman-compose"}
             (entry "tools/cli"))
          "with the defaults of the runner the scope names, not the boot table's"))
    (testing "saying where each value the scope didn't set came from"
      (is (= {"runner" [:boot "ruby-lsp"] "command" [:boot "ruby-lsp"] "compose" [:defaults "compose"]}
             (:sources (meta (entry "services/api")))))
      (is (= {"service" [:boot "ruby-lsp"] "command" [:boot "ruby-lsp"]}
             (:sources (meta (entry "tools/cli"))))))
    (testing "with paths from a runner's defaults, but not the runner"
      (is (= {"runner" "r" "service" "app" "command" "ruby-lsp" "paths" {"." "/src"} "x" "d" "y" "s"}
             (entry "other")))
      (is (= {"service" [:boot "ruby-lsp"] "command" [:boot "ruby-lsp"] "paths" [:defaults "r"] "x" [:defaults "r"]}
             (:sources (meta (entry "other"))))))
    (testing "leaving out what the scopes now hold"
      (is (= {"languages" ["ruby"]} (get-in c ["servers" "ruby-lsp"])))
      (is (not-any? #(contains? % "defaults") (vals (get c "runners")))))))

(deftest unsets-values-set-to-false
  (let [c (config/parse (str "
[servers.ruby-lsp]
languages = ['ruby']
boot = { runner = 'r', service = 'app', command = 'ruby-lsp', paths = { '.' = '/srv' }, x = false }

[runners.r]
defaults = { x = 'd', y = 'd' }
start = ['{cfg:command}']

[scopes.a.ruby-lsp]
[scopes.b.ruby-lsp]
runner = 'host'
service = false
paths = false
[scopes.c.ruby-lsp]
x = 's'
" compose-and-host))
        entry #(get-in c ["scopes" % "ruby-lsp"])]
    (testing "from a lower layer, whether the boot table or the scope unsets it"
      (is (= {"runner" "r" "service" "app" "command" "ruby-lsp" "paths" {"." "/srv"} "y" "d"}
             (entry "a")))
      (is (= {"runner" "host" "command" "ruby-lsp"} (entry "b"))))
    (testing "and from :sources"
      (is (= {"runner" [:boot "ruby-lsp"] "service" [:boot "ruby-lsp"] "command" [:boot "ruby-lsp"]
              "paths" [:boot "ruby-lsp"] "y" [:defaults "r"]}
             (:sources (meta (entry "a")))))
      (is (= {"command" [:boot "ruby-lsp"]} (:sources (meta (entry "b"))))))
    (testing "unless a higher layer sets it again"
      (is (= "s" (get (entry "c") "x")))
      (is (not (contains? (:sources (meta (entry "c"))) "x"))))))

(deftest layers-paths-from-runner-defaults
  (let [c (config/parse "
[servers.ruby-lsp]
languages = ['ruby']
boot = { runner = 'c', command = 'ruby-lsp' }

[runners.c]
defaults = { compose = ['docker', 'compose', '--file', 'dev/compose.yml'], paths = { '.' = '/src' } }
start = ['{cfg:compose}', 'exec', '-T', '{cfg:service}', '{cfg:command}']

[scopes.'services/api'.ruby-lsp]
service = 'api'
[scopes.'services/billing'.ruby-lsp]
service = 'billing'
paths = { 'services/billing' = '/app' }
[scopes.'services/legacy'.ruby-lsp]
service = 'legacy'
paths = false
[scopes.'tools/cli'.ruby-lsp]
runner = false
")
        entry #(get-in c ["scopes" % "ruby-lsp"])]
    (testing "under every scope that uses the runner"
      (is (= {"." "/src"} (get (entry "services/api") "paths")))
      (is (= [:defaults "c"] (get-in (meta (entry "services/api")) [:sources "paths"]))))
    (testing "unless a scope replaces or unsets them"
      (is (= {"services/billing" "/app"} (get (entry "services/billing") "paths")))
      (is (not (contains? (:sources (meta (entry "services/billing"))) "paths")))
      (is (not (contains? (entry "services/legacy") "paths"))))
    (testing "but not under a scope that doesn't use the runner"
      (is (= {"command" "ruby-lsp"} (entry "tools/cli")))
      (is (= {"command" [:boot "ruby-lsp"]} (:sources (meta (entry "tools/cli"))))))
    (testing "without warning that the runner's templates don't use them"
      (is (empty? (config/warnings c))))))

(deftest runs-a-server-type-no-scope-names-across-the-repo
  (let [c (config/parse (str "
[servers.ruby-lsp]
languages = ['ruby']
boot = { runner = 'compose', service = 'app', command = 'ruby-lsp' }
[servers.rubocop]
languages = ['ruby']
boot = { command = 'rubocop' }
[servers.ts-ls]
languages = ['typescript']
boot = { command = 'ts-ls' }

[scopes.'apps/web'.ts-ls]
[scopes.'.'.rubocop]
command = 'rubocop --lsp'
" compose-and-host))
        entry #(get-in c ["scopes" %1 %2])]
    (testing "with its boot table, over its runner's defaults, as the whole boot config"
      (is (= {"runner" "compose" "service" "app" "command" "ruby-lsp" "compose" ["docker" "compose"]}
             (entry "." "ruby-lsp"))))
    (testing "saying it's implicit, and where its values came from"
      (is (= {:implicit true
              :sources {"runner" [:boot "ruby-lsp"] "service" [:boot "ruby-lsp"]
                        "command" [:boot "ruby-lsp"] "compose" [:defaults "compose"]}}
             (meta (entry "." "ruby-lsp"))))
      (is (config/implicit? c ["ruby-lsp" "."])))
    (testing "only where no scope names the server type"
      (is (= #{"ruby-lsp" "rubocop"} (set (keys (get-in c ["scopes" "."])))))
      (is (= {"ts-ls" {"command" "ts-ls"}} (get-in c ["scopes" "apps/web"])))
      (is (nil? (config/scope-for c "ts-ls" "lib/a.ts")))
      (is (not (config/implicit? c ["rubocop" "."])) "even a scope at the repo root"))
    (testing "with problems reported against the server type"
      (is (= #{"server type \"b\", which no scope names: when run directly: no value for {cfg:command}"}
             (errors "[servers.a]\nlanguages = ['x']\nboot = { command = 'a' }\n[servers.b]\nlanguages = ['x']\n")))
      (is (= ["server type \"a\", which no scope names: \"image\" isn't used when run directly"]
             (config/warnings (config/parse "[servers.a]\nlanguages = ['x']\nboot = { command = 'a', image = 'i' }\n")))))
    (testing "and never in the configs that ship as examples"
      (doseq [dir ["test/heddle/fixtures/design" "examples/monorepo" "examples/ruby-compose"]
              :let [c (config/read-config dir)]
              slot (spec/slots c)]
        (is (not (config/implicit? c slot)) (str dir " " slot))))))

(defn reprinted
  "The config in the TOML text `s`, and the config parsed from what
  `expanded-toml` prints for it."
  [s]
  (let [c (config/parse s)]
    [c (config/parse (config/expanded-toml c s))]))

(def ^:private layered "
[servers.ruby-lsp]
languages = ['ruby']
boot = { runner = 'c', command = ['bundle', 'exec', 'ruby-lsp'], x = false }

[servers.rubocop]
languages = ['ruby']
boot = { command = 'rubocop' }

[runners.c]
defaults = { compose = ['docker', 'compose'], paths = { '.' = '/src' }, x = 'd' }
start = ['{cfg:compose}', 'exec', '{cfg:service}', '{cfg:command}']

[scopes.'services/api'.ruby-lsp]
service = 'api'
[scopes.'tools/cli'.ruby-lsp]
runner = false
")

(deftest prints-the-expanded-config
  (is (= "[servers.rubocop]
languages = [\"ruby\"]

[servers.ruby-lsp]
languages = [\"ruby\"]

[runners.c]
start = [\"{cfg:compose}\", \"exec\", \"{cfg:service}\", \"{cfg:command}\"]

# rubocop runs across the whole repo, since no scope names it
[scopes.\".\".rubocop]
# heddle runs the command directly, with no runner
command = \"rubocop\"  # servers.rubocop.boot (line 8)

[scopes.\"services/api\".ruby-lsp]
runner = \"c\"                              # servers.ruby-lsp.boot (line 4)
command = [\"bundle\", \"exec\", \"ruby-lsp\"]  # servers.ruby-lsp.boot (line 4)
compose = [\"docker\", \"compose\"]           # runners.c.defaults (line 11)
paths = { \".\" = \"/src\" }                  # runners.c.defaults (line 11)
service = \"api\"                           # scopes.\"services/api\".ruby-lsp (line 15)

[scopes.\"tools/cli\".ruby-lsp]
# heddle runs the command directly, with no runner
command = [\"bundle\", \"exec\", \"ruby-lsp\"]  # servers.ruby-lsp.boot (line 4)"
         (config/expanded-toml (config/parse layered) layered))))

(deftest prints-a-config-that-expands-the-same
  (doseq [s [layered
             (slurp "test/heddle/fixtures/design/.heddle.toml")
             (slurp "examples/monorepo/.heddle.toml")
             (slurp "examples/ruby-compose/.heddle.toml")]
          :let [[c again] (reprinted s)]]
    (is (= c again))
    (is (= [] (config/warnings again)))))

(deftest unknown-server-types
  (is (= ["nosuch"] (config/unknown-server-types design ["ruby-lsp" "nosuch"])))
  (is (empty? (config/unknown-server-types design []))))

(deftest priorities
  (let [c (config/parse "
[servers.a]
languages = ['x']
boot = { command = 'a' }
[servers.b]
languages = ['x']
boot = { command = 'b' }
priority = -1
[servers.c]
languages = ['x']
boot = { command = 'c' }
priority = 2
[servers.d]
languages = ['x']
boot = { command = 'd' }
")]
    (is (= ["c" "a" "d" "b"] (config/by-priority c ["a" "b" "c" "d"]))
        "highest first, then by name, with 0 as the default")))

(deftest scopes
  (testing "the longest scope that starts the server type"
    (is (= "services/api" (config/scope-for design "ruby-lsp" "services/api/app/models/user.rb")))
    (is (= "." (config/scope-for design "rubocop" "services/api/app/models/user.rb"))))
  (testing "a scope must contain the file"
    (is (nil? (config/scope-for design "ruby-lsp" "lib/tasks/db.rake")))
    (is (nil? (config/scope-for design "ruby-lsp" "services/apiary/x.rb"))))
  (testing "a nested scope wins over its parent, whatever the names' lengths"
    (let [c (config/parse "
[servers.s]
languages = ['x']
boot = { command = 's' }

[scopes.'abcdef'.s]
[scopes.'abcdef/g'.s]
[scopes.'.'.s]
")]
      (is (= "abcdef/g" (config/scope-for c "s" "abcdef/g/h")))
      (is (= "abcdef" (config/scope-for c "s" "abcdef/h")))
      (is (= "." (config/scope-for c "s" "z")))))
  (testing "the longest scope and those that also serve a directory containing the file, sorted"
    (let [c (config/parse "
[servers.s]
languages = ['x']
boot = { command = 's' }
[servers.t]
languages = ['x']
boot = { command = 't' }

[scopes.a.s]
also = ['shared', 'b/vendor']
[scopes.b.s]
also = ['shared/one', 'other']
[scopes.c.t]
also = ['shared']

[servers.u]
languages = ['x']
boot = { command = 'u', also = ['shared'] }
[scopes.d.u]
[scopes.e.u]
also = false
")]
      (is (= ["a" "b"] (config/scopes-for c "s" "shared/one/x")))
      (is (= ["a"] (config/scopes-for c "s" "shared/two/x")))
      (is (= ["c"] (config/scopes-for c "t" "shared/x")))
      (is (empty? (config/scopes-for c "s" "sharedx/x")))
      (is (= ["a" "b"] (config/scopes-for c "s" "b/vendor/x")))
      (is (= ["b"] (config/scopes-for c "s" "b/x")))
      (is (= ["d"] (config/scopes-for c "u" "shared/x")) "from the boot table, unless a scope unsets it"))))

(deftest trace-path
  (is (= "/r/tmp/trace.jsonl" (config/trace-path (config/parse "trace = { path = 'tmp/trace.jsonl' }") "/r")))
  (is (= "/var/trace.jsonl" (config/trace-path (config/parse "trace = { path = '/var/trace.jsonl' }") "/r")))
  (is (nil? (config/trace-path design "/r"))))

(deftest read-config
  (is (map? design))
  (is (nil? (config/read-config "test/heddle")) "no config"))

(deftest reports-invalid-toml-with-position
  (is (= #{"TOML syntax error at line 3, column 8"}
         (errors "[servers.a]\nlanguages = ['x']\nboot = = 1\n"))))

(deftest reports-structural-problems
  (testing "server types"
    (is (= #{"server type \"a\": languages must be a list of strings"
             "server type \"a\": boot must be a table"
             "server type \"a\": priority must be an integer"
             "server type \"b\" matches no files; give it languages or globs"}
           (errors "
[servers.a]
languages = 'ruby'
boot = 1
priority = 'high'

[servers.b]
")))
    (is (= #{"server type \"ruby-lsp\": unknown key \"langauges\""}
           (errors "
[servers.ruby-lsp]
langauges = ['ruby']
globs = ['**/Gemfile']
"))
        "a misspelt key, even when the server type still matches files"))
  (testing "scopes"
    (is (= #{"scope \"a/\" must be a directory relative to the repo root, such as \"services/api\" or \".\""
             "scope \"../a\" must be a directory relative to the repo root, such as \"services/api\" or \".\""
             "scope \".\", server type \"b\" is not a server type"
             "scope \".\", server type \"a\" must be a table"}
           (errors "
[servers.a]
languages = ['x']

[scopes.'a/'.a]
[scopes.'../a'.a]

[scopes.'.']
a = 1
b = {}
"))))
  (testing "a runner's paths"
    (are [defaults error] (= #{error} (errors (str "[runners.r]\nstart = ['x']\ndefaults = " defaults "\n")))
      "{ paths = {} }"                         "runner \"r\": defaults: paths can't be empty; use paths = false to unset it"
      "{ paths = '/src' }"                     "runner \"r\": defaults: paths must be a table"
      "{ paths = { '/abs' = '/src' } }"        "runner \"r\": defaults: paths entry \"/abs\" must be relative to the repo root"
      "{ paths = { '.' = 'src' } }"            "runner \"r\": defaults: paths entry \".\" must map to an absolute directory")
    (is (nil? (errors "[runners.r]\nstart = ['x']\ndefaults = { paths = false }\n")))
    (is (nil? (errors "[runners.r]\nstart = ['x']\ndefaults = { paths = { '.' = '/src' } }\n"))))
  (testing "runners"
    (is (= #{"runner \"r\": unknown key \"stat\""
             "runner \"r\": has no start template"
             "runner \"r\": read: unknown placeholder {client:file}"
             "runner \"p\": start: {read:file} is only available in read"
             "runner \"p\": start: unknown placeholder {srv:scope}"}
           (errors "
[runners.r]
stat = []
read = ['{client:file}']

[runners.p]
start = ['{read:file}', '--root={srv:scope}']
"))))
  (testing "sections"
    (is (= #{"unknown section \"other\"" "servers must be a table"}
           (errors "servers = 1\n[other]\n"))))
  (testing "the cache"
    (is (nil? (errors "cache = { env = 'XDG_CACHE_HOME' }")))
    (is (nil? (errors "cache = { path = '/var/cache' }")))
    (is (nil? (errors "[cache]\npath = '.cache/lsp'\n")))
    (is (nil? (errors "cache = { xdg = true }")))
    (are [toml error] (= #{error} (errors toml))
      "cache = '/var/cache'"                  "cache must be a table, such as { xdg = true }"
      "[cache]"                               "cache needs exactly one of env, path or xdg"
      "cache = { env = 'A', path = '/b' }"    "cache needs exactly one of env, path or xdg"
      "cache = { xdg = true, env = 'A' }"     "cache needs exactly one of env, path or xdg"
      "cache = { xdg = true, path = '/b' }"   "cache needs exactly one of env, path or xdg"
      "cache = { env = 'A', dir = '/b' }"     "cache: unknown key \"dir\""
      "cache = { env = '' }"                  "cache: env must be the name of an environment variable"
      "cache = { xdg = false }"               "cache: xdg must be true"
      "cache = { xdg = 'yes' }"               "cache: xdg must be true"
      "cache = { path = 1 }"                  "cache: path must be a string"
      "cache = { path = '~/.cache' }"         "cache: path can't start with ~, which isn't expanded; use xdg, env or an absolute path"
      "cache = { path = '../cache' }"         "cache: path must be absolute, or relative to the config's directory, with no empty, . or .. segments"
      "cache = { path = '/var/cache/' }"      "cache: path must be absolute, or relative to the config's directory, with no empty, . or .. segments"))
  (testing "tracing"
    (is (nil? (errors "trace = { path = '/tmp/trace.jsonl' }")))
    (is (nil? (errors "[trace]\npath = 'tmp/trace.jsonl'\n")))
    (are [toml error] (= #{error} (errors toml))
      "trace = 'trace.jsonl'"                 "trace must be a table, such as { path = \"trace.jsonl\" }"
      "[trace]"                               "trace needs a path"
      "trace = { path = 'a', file = 'b' }"    "trace: unknown key \"file\""
      "trace = { path = 1 }"                  "trace: path must be a string"
      "trace = { path = '~/trace.jsonl' }"    "trace: path can't start with ~, which isn't expanded; use an absolute path"
      "trace = { path = '../trace.jsonl' }"   "trace: path must name a file, absolute or relative to the config's directory, with no empty, . or .. segments"
      "trace = { path = '' }"                 "trace: path must name a file, absolute or relative to the config's directory, with no empty, . or .. segments"
      "trace = { path = '.' }"                "trace: path must name a file, absolute or relative to the config's directory, with no empty, . or .. segments"
      "trace = { path = '/' }"                "trace: path must name a file, absolute or relative to the config's directory, with no empty, . or .. segments"
      "trace = { path = 'tmp/' }"             "trace: path must name a file, absolute or relative to the config's directory, with no empty, . or .. segments"))
  (testing "the message lists every problem"
    (let [msg (try (config/parse "x = 1\ny = 2\n" "here.toml")
                   (catch clojure.lang.ExceptionInfo e (ex-message e)))]
      (is (str/starts-with? msg "invalid config in here.toml:\n"))
      (is (str/includes? msg "unknown section \"x\""))
      (is (str/includes? msg "unknown section \"y\"")))))

(defn- scope-errors
  "Errors for a scope `s` that starts the server type `a`, given the TOML for
  `a`'s boot config and anything after it."
  [boot & more]
  (errors (apply str "[servers.a]\nlanguages = ['x']\n[scopes.s]\na = " boot "\n" more)))

(deftest checks-scopes-against-runners
  (testing "the runner has to exist, if there is one"
    (is (nil? (scope-errors "{ command = 'a' }")))
    (is (= #{"scope \"s\", server type \"a\": unknown runner \"nope\""}
           (scope-errors "{ runner = 'nope' }")))
    (is (= #{"scope \"s\", server type \"a\": unknown runner \"compose\""}
           (scope-errors "{ runner = 'compose', service = 'api', command = 'a' }")))
    (is (= #{"scope \"s\", server type \"a\": runner must be a string"}
           (scope-errors "{ runner = 1 }")
           (scope-errors "{ runner = {} }"))))
  (testing "without a runner, the command has to be given"
    (is (= #{"scope \"s\", server type \"a\": when run directly: no value for {cfg:command}"}
           (scope-errors "{ service = 'api' }"))))
  (testing "a value no template uses is allowed"
    (is (nil? (scope-errors "{ command = 'a', service = 'api' }"))))
  (testing "every {cfg:…} a template uses needs a value"
    (is (= #{"scope \"s\", server type \"a\": runner \"compose\", start: no value for {cfg:service}"
             "scope \"s\", server type \"a\": runner \"compose\", read: no value for {cfg:service}"}
           (scope-errors "{ runner = 'compose', command = 'a' }" compose-and-host))))
  (testing "paths"
    (is (= #{"scope \"s\", server type \"a\": paths entry \"/abs\" must be relative to the repo root"
             "scope \"s\", server type \"a\": paths entry \"s\" must map to an absolute directory"}
           (scope-errors "{ command = 'a', paths = { '/abs' = '/app', 's' = 'app' } }")))
    (is (= #{"scope \"s\", server type \"a\": paths don't cover the scope's directory"}
           (scope-errors "{ command = 'a', paths = { 'other' = '/app' } }")))
    (is (= #{"scope \"s\", server type \"a\": paths can't be empty; use paths = false to unset it"}
           (scope-errors "{ command = 'a', paths = {} }")
           (errors "[servers.a]\nlanguages = ['x']\n[scopes.s.a]\ncommand = 'a'\n[scopes.s.a.paths]\n")))
    (is (= #{"scope \"s\", server type \"a\": paths must be a table"}
           (scope-errors "{ command = 'a', paths = true }"))))
  (testing "also"
    (is (nil? (scope-errors "{ command = 'a', also = ['shared', 'lib/x'] }")))
    (is (nil? (scope-errors "{ command = 'a', also = ['shared'], paths = { '.' = '/src' } }")))
    (is (nil? (scope-errors "{ command = 'a', also = ['shared'], paths = false }")))
    (is (nil? (scope-errors "{ command = 'a', also = false }")))
    (is (= #{"scope \"s\", server type \"a\": also must be a list of directories relative to the repo root, such as [\"shared/analytics\"]"}
           (scope-errors "{ command = 'a', also = 'shared' }")
           (scope-errors "{ command = 'a', also = ['/shared'] }")
           (scope-errors "{ command = 'a', also = ['../shared'] }")))
    (is (= #{"scope \"s\", server type \"a\": paths don't cover \"shared\", which is in also"}
           (scope-errors "{ command = 'a', also = ['shared', 'lib'], paths = { 's' = '/app', 'lib' = '/lib' } }")
           (scope-errors "{ runner = 'r', also = ['shared'] }"
                         "[runners.r]\nstart = ['x']\ndefaults = { paths = { 's' = '/app' } }\n")))
    (is (= #{"scope \"s\", server type \"a\": paths must be a table"}
           (scope-errors "{ command = 'a', also = ['shared'], paths = true }"))
        "only once paths are valid"))
  (testing "a value a template needs can't be unset"
    (is (= #{"scope \"s\", server type \"a\": when run directly: no value for {cfg:command}"}
           (scope-errors "{ command = false }"))))
  (testing "values follow the placeholder rules"
    (is (= #{"scope \"s\", server type \"a\": runner \"wrap\", start: {cfg:command} is a list, which has to make up a whole element"}
           (scope-errors "{ runner = 'wrap', command = ['x', 'y'] }"
                         "[runners.wrap]\nstart = ['sh', '-c', 'run {cfg:command}']\n")))
    (is (= #{"scope \"s\", server type \"a\": when run directly: {read:file} is only available in read"
             "scope \"s\", server type \"a\": when run directly: the value of {cfg:command} can't use {cfg:other}"}
           (scope-errors "{ command = ['{read:file}', '--x={cfg:other}'], other = 'o' }")))))

(deftest warns-about-unused-values
  (testing "a value the scope's runner never uses"
    (is (= ["scope \"tools/cli\", server type \"ruby-lsp\": \"service\" isn't used by runner \"host\""]
           (config/warnings (config/parse (str "
[servers.ruby-lsp]
languages = ['ruby']
boot = { runner = 'compose', service = 'api', command = 'ruby-lsp' }

[scopes.'services/api'.ruby-lsp]
[scopes.'tools/cli'.ruby-lsp]
runner = 'host'
" compose-and-host))))))
  (testing "a value that only the runner a scope cancels would use"
    (is (= ["scope \"tools/cli\", server type \"ruby-lsp\": \"service\" isn't used when run directly"]
           (config/warnings (config/parse "
[servers.ruby-lsp]
languages = ['ruby']
boot = { runner = 'compose', service = 'api', command = 'ruby-lsp' }

[scopes.'tools/cli'.ruby-lsp]
runner = false
")))))
  (testing "nothing for a value the scope unsets"
    (is (empty? (config/warnings (config/parse (str "
[servers.ruby-lsp]
languages = ['ruby']
boot = { runner = 'compose', service = 'api', command = 'ruby-lsp' }

[scopes.'tools/cli'.ruby-lsp]
runner = 'host'
service = false
" compose-and-host))))))
  (testing "nothing when every value is used, counting runner, paths and values that replace a default"
    (is (empty? (config/warnings design))))
  (testing "nothing for also"
    (is (empty? (config/warnings (config/parse "
[servers.s]
languages = ['x']

[scopes.a.s]
command = 's'
also = ['shared']
")))))
  (testing "nothing for a runner's default that its templates never use"
    (is (empty? (config/warnings (config/parse "
[servers.s]
languages = ['x']

[runners.r]
defaults = { spare = 'd' }
start = ['{cfg:command}']

[scopes.'.'.s]
runner = 'r'
command = 's'
")))))
  (testing "as one message"
    (is (= "unused values in here.toml:\n  a\n  b"
           (config/warnings-message "here.toml" ["a" "b"])))))
