(ns heddle.spec-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [heddle.config :as config]
            [heddle.spec :as spec]))

(def ^:private design (config/read-config "test/heddle/fixtures/design"))

(defn- spec-for [config slot]
  (spec/spec config slot "/r" "/cache"))

(defn- command
  "The arguments for `template` for the `slot` in `config`, run as backend
  heddle-1."
  ([config slot template]
   (command config slot template {}))
  ([config slot template runtime]
   (spec/command (spec-for config slot) template (merge {:backend-id "heddle-1"} runtime))))

(deftest specs
  (let [s (spec-for design ["ruby-lsp" "services/api"])]
    (testing "describe where the backend runs and how paths map"
      (is (= ["ruby-lsp" "services/api"] (:slot s)))
      (is (= "/r" (:repo s)))
      (is (= "/r/services/api" (:dir s)))
      (is (= "/app" (:root s)))
      (is (= {"services/api" "/app"} (:paths s)))
      (is (= "/cache/ruby-lsp/services/api/=" (:cache s))))
    (testing "keep only the runner's templates"
      (is (= #{"start" "read"} (set (keys (:templates s))))))
    (testing "say which files the server type handles"
      (is (= ["ruby"] (:languages s)))
      (is (= ["**/Gemfile" "**/*.rake"] (:globs s)))))
  (testing "give a server type without globs none"
    (is (= [] (:globs (spec-for design ["rubocop" "."])))))
  (testing "root the root scope where paths map it"
    (is (= "/src" (:root (spec-for design ["rubocop" "."])))))
  (testing "root a scope without paths at its own directory"
    (is (= "/r/services/legacy" (:root (spec-for design ["ruby-lsp" "services/legacy"])))))
  (testing "take the scope's values over the server type's"
    (is (= ["ruby-lsp"] (get-in (spec-for design ["ruby-lsp" "services/legacy"]) [:cfg "command"])))
    (is (= ["bundle" "exec" "ruby-lsp"]
           (get-in (spec-for design ["ruby-lsp" "services/api"]) [:cfg "command"])))))

(deftest slots
  (testing "of every server type"
    (is (= #{["ruby-lsp" "services/api"] ["ruby-lsp" "services/billing"] ["ruby-lsp" "services/legacy"]
             ["typescript" "services/web"] ["rubocop" "."]}
           (set (spec/slots design))
           (set (spec/slots design [])))))
  (testing "of only the server types served"
    (is (= #{["ruby-lsp" "services/api"] ["ruby-lsp" "services/billing"] ["ruby-lsp" "services/legacy"]}
           (set (spec/slots design #{"ruby-lsp" "nosuch"})))))
  (testing "including those of a server type that no scope names"
    (let [c (config/parse "
[servers.s]
languages = ['x']
boot = { command = 's' }
[servers.t]
languages = ['x']
boot = { command = 't' }

[scopes.a.s]
")]
      (is (= #{["s" "a"] ["t" "."]} (set (spec/slots c))))
      (is (= [["t" "."]] (spec/slots c #{"t"}))))))

(deftest specs-by-slot
  (let [specs (spec/specs design "/r" "/cache" #{"rubocop"})]
    (is (= [["rubocop" "."]] (keys specs)))
    (is (= (spec-for design ["rubocop" "."]) (get specs ["rubocop" "."])))))

(deftest commands
  (testing "the example in DESIGN.md"
    (is (= ["docker" "compose" "exec" "-T" "--workdir" "/app" "api" "bundle" "exec" "ruby-lsp"]
           (command design ["ruby-lsp" "services/api"] "start")))
    (is (= ["docker" "compose" "exec" "-T" "api" "cat" "/usr/lib/ruby/x.rb"]
           (command design ["ruby-lsp" "services/api"] "read" {:file "/usr/lib/ruby/x.rb"})))
    (is (nil? (command design ["ruby-lsp" "services/api"] "prepare")))
    (is (= ["ruby-lsp"] (command design ["ruby-lsp" "services/legacy"] "start")))
    (is (= ["docker" "compose" "exec" "-T" "--workdir" "/src" "lints" "rubocop" "--lsp"]
           (command design ["rubocop" "."] "start")))
    (is (= ["docker" "run" "--rm" "--interactive" "--name" "heddle-1" "--volume" "/r:/src"
            "--workdir" "/src/services/web" "web-tools" "typescript-language-server" "--stdio"]
           (command design ["typescript" "services/web"] "start"))))
  (testing "the command alone, with no runner"
    (let [c (config/parse "
[servers.s]
languages = ['x']
boot = { runner = 'compose', service = 'app', command = 's' }
[servers.t]
languages = ['x']
boot = { command = 't' }

[scopes.'.']
s = { runner = false }
t = { paths = { '.' = '/src' } }
")]
      (is (= ["s"] (command c ["s" "."] "start")) "when the scope cancels the server type's runner")
      (is (= ["t"] (command c ["t" "."] "start")) "when nothing names one")
      (is (= "/src" (:root (spec-for c ["t" "."]))) "with paths as given")
      (is (nil? (command c ["t" "."] "read")))))
  (testing "a project's runner replaces a built-in of the same name"
    (let [c (config/parse "
[servers.s]
languages = ['x']

[runners.host]
start = ['env', '{cfg:command}']

[scopes.'.']
s = { runner = 'host', command = 's' }
[scopes.a]
s = { command = 's' }
")]
      (is (= ["env" "s"] (command c ["s" "."] "start")))
      (is (= ["s"] (command c ["s" "a"] "start")) "and a scope with no runner doesn't use it")
      (is (contains? (get c "runners") "compose") "the other built-ins remain")))
  (testing "a runner's defaults fill in values the boot config leaves out"
    (let [c (config/parse "
[servers.s]
languages = ['x']

[scopes.a]
s = { runner = 'docker-run', image = 'i', command = 's' }
")]
      (is (= ["docker" "run" "--rm" "--interactive" "--name" "heddle-1" "--workdir" "/r/a" "i" "s"]
             (command c ["s" "a"] "start")))))
  (testing "a runner's defaults can give paths, which root the server and aren't template values"
    (let [c (config/parse "
[servers.s]
languages = ['x']
boot = { runner = 'c', command = 's' }

[runners.c]
defaults = { paths = { '.' = '/src' } }
start = ['run', '{server:scope}', '{cfg:command}']

[scopes.a.s]
")
          s (spec-for c ["s" "a"])]
      (is (= {"." "/src"} (:paths s)))
      (is (= "/src/a" (:root s)))
      (is (= {"command" "s"} (:cfg s)))
      (is (= ["run" "/src/a" "s"] (command c ["s" "a"] "start"))))))

(deftest cache-dirs
  (let [dir (spec/cache-dir "/cache" ["ruby" "services/api"])]
    (testing "are keyed by server type and scope, under the repo's cache"
      (is (= "/cache/ruby/services/api/=" dir))
      (is (= dir (spec/cache-dir "/cache/" ["ruby" "services/api"]))))
    (testing "give the root scope no directories"
      (is (= "/cache/ruby/=" (spec/cache-dir "/cache" ["ruby" "."]))))
    (testing "differ for each server type and scope"
      (is (= 4 (count (distinct [dir
                                 (spec/cache-dir "/cache" ["rubocop" "services/api"])
                                 (spec/cache-dir "/cache" ["ruby" "services"])
                                 (spec/cache-dir "/cache" ["ruby" "."])])))))
    (testing "don't nest one scope inside another"
      (is (not (str/starts-with? dir (str (spec/cache-dir "/cache" ["ruby" "services"]) "/")))))
    (testing "encode a `=` in a scope, which would otherwise end it early"
      (is (= "/cache/ruby/a/=3D/b/=" (spec/cache-dir "/cache" ["ruby" "a/=/b"]))))
    (testing "keep a server type to one segment of its own"
      (is (= "/cache/a=2Fb=3D/=" (spec/cache-dir "/cache" ["a/b=" "."])))
      (is (= "/cache/=../=" (spec/cache-dir "/cache" [".." "."])))
      (is (= "/cache/=/services/=" (spec/cache-dir "/cache" ["" "services"]))))
    (testing "leave other characters as they are"
      (is (= "/cache/rubý/my app%/=" (spec/cache-dir "/cache" ["rubý" "my app%"]))))))
