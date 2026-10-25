(ns monorepo-lsp.config-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [monorepo-lsp.config :as config]))

(def design (config/read-config "test/monorepo_lsp/fixtures/design"))

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
  (is (= ["x"] (config/languages (config/parse "
[servers.started]
languages = ['x']
boot = { runner = 'host', command = 's' }
[servers.unstarted]
languages = ['y']
[servers.by-glob]
globs = ['*.z']
boot = { runner = 'host', command = 's' }

[scopes.'.'.started]
[scopes.'.'.by-glob]
")))
      "only server types that a scope starts, and only their languages"))

(deftest priorities
  (let [c (config/parse "
[servers.a]
languages = ['x']
[servers.b]
languages = ['x']
priority = -1
[servers.c]
languages = ['x']
priority = 2
[servers.d]
languages = ['x']
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
boot = { runner = 'host', command = 's' }

[scopes.'abcdef'.s]
[scopes.'abcdef/g'.s]
[scopes.'.'.s]
")]
      (is (= "abcdef/g" (config/scope-for c "s" "abcdef/g/h")))
      (is (= "abcdef" (config/scope-for c "s" "abcdef/h")))
      (is (= "." (config/scope-for c "s" "z"))))))

(deftest boot-configs
  (is (= {"command" ["bundle" "exec" "ruby-lsp"]
          "runner" "compose"
          "service" "api"
          "paths" {"services/api" "/app"}}
         (config/boot-config design "ruby-lsp" "services/api")))
  (is (= ["ruby-lsp"] (get (config/boot-config design "ruby-lsp" "services/legacy") "command"))
      "a scope's value replaces the server type's"))

(deftest read-config
  (is (map? design))
  (is (nil? (config/read-config "test/monorepo_lsp")) "no config"))

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
  (testing "the runner has to exist"
    (is (= #{"scope \"s\", server type \"a\": names no runner"}
           (scope-errors "{ command = 'a' }")))
    (is (= #{"scope \"s\", server type \"a\": unknown runner \"nope\""}
           (scope-errors "{ runner = 'nope' }"))))
  (testing "a value no template uses is allowed"
    (is (nil? (scope-errors "{ runner = 'host', command = 'a', service = 'api' }"))))
  (testing "every {cfg:…} a template uses needs a value"
    (is (= #{"scope \"s\", server type \"a\": runner \"compose\", start: no value for {cfg:service}"
             "scope \"s\", server type \"a\": runner \"compose\", read: no value for {cfg:service}"}
           (scope-errors "{ runner = 'compose', command = 'a' }"))))
  (testing "paths"
    (is (= #{"scope \"s\", server type \"a\": paths entry \"/abs\" must be relative to the repo root"
             "scope \"s\", server type \"a\": paths entry \"s\" must map to an absolute directory"}
           (scope-errors "{ runner = 'host', command = 'a', paths = { '/abs' = '/app', 's' = 'app' } }")))
    (is (= #{"scope \"s\", server type \"a\": paths don't cover the scope's directory"}
           (scope-errors "{ runner = 'host', command = 'a', paths = { 'other' = '/app' } }"))))
  (testing "values follow the placeholder rules"
    (is (= #{"scope \"s\", server type \"a\": runner \"wrap\", start: {cfg:command} is a list, which has to make up a whole element"}
           (scope-errors "{ runner = 'wrap', command = ['x', 'y'] }"
                         "[runners.wrap]\nstart = ['sh', '-c', 'run {cfg:command}']\n")))
    (is (= #{"scope \"s\", server type \"a\": runner \"host\", start: {read:file} is only available in read"
             "scope \"s\", server type \"a\": runner \"host\", start: the value of {cfg:command} can't use {cfg:other}"}
           (scope-errors "{ runner = 'host', command = ['{read:file}', '--x={cfg:other}'], other = 'o' }")))))

(def ^:private runtime {:repo "/r" :backend-id "monorepo-lsp-1"})

(deftest commands
  (testing "the example in DESIGN.md"
    (is (= ["docker" "compose" "exec" "-T" "--workdir" "/app" "api" "bundle" "exec" "ruby-lsp"]
           (config/command design "ruby-lsp" "services/api" "start" runtime)))
    (is (= ["docker" "compose" "exec" "-T" "api" "cat" "/usr/lib/ruby/x.rb"]
           (config/command design "ruby-lsp" "services/api" "read" (assoc runtime :file "/usr/lib/ruby/x.rb"))))
    (is (nil? (config/command design "ruby-lsp" "services/api" "prepare" runtime)))
    (is (= ["ruby-lsp"] (config/command design "ruby-lsp" "services/legacy" "start" runtime)))
    (is (= ["docker" "compose" "exec" "-T" "--workdir" "/src" "lints" "rubocop" "--lsp"]
           (config/command design "rubocop" "." "start" runtime)))
    (is (= ["docker" "run" "--rm" "--interactive" "--name" "monorepo-lsp-1" "--volume" "/r:/src"
            "--workdir" "/src/services/web" "web-tools" "typescript-language-server" "--stdio"]
           (config/command design "typescript" "services/web" "start" runtime))))
  (testing "a project's runner replaces a built-in of the same name"
    (let [c (config/parse "
[servers.s]
languages = ['x']

[runners.host]
start = ['env', '{cfg:command}']

[scopes.'.']
s = { runner = 'host', command = 's' }
")]
      (is (= ["env" "s"] (config/command c "s" "." "start" runtime)))
      (is (contains? (get c "runners") "compose") "the other built-ins remain")))
  (testing "a runner's defaults fill in values the boot config leaves out"
    (let [c (config/parse "
[servers.s]
languages = ['x']

[scopes.a]
s = { runner = 'docker-run', image = 'i', command = 's' }
")]
      (is (= ["docker" "run" "--rm" "--interactive" "--name" "monorepo-lsp-1" "--workdir" "/r/a" "i" "s"]
             (config/command c "s" "a" "start" runtime))))))

(deftest server-scopes
  (is (= "/app" (config/server-scope design "ruby-lsp" "services/api" "/r")))
  (is (= "/r/services/legacy" (config/server-scope design "ruby-lsp" "services/legacy" "/r")))
  (is (= "/src" (config/server-scope design "rubocop" "." "/r"))))
