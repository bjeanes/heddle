;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.readme-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [heddle.config :as config]
            [heddle.config-test :as config-test]
            [heddle.spec :as spec]))

(def ^:private readme (slurp "README.md"))

(defn- toml-after
  "The contents of the TOML block on the line after `<!-- marker -->`."
  [marker]
  (let [opening (str "<!-- " marker " -->\n```toml\n")
        at (str/index-of readme opening)
        body (some-> at (+ (count opening)))
        end (some->> body (str/index-of readme "\n```"))]
    (if end
      (subs readme body (inc end))
      (throw (ex-info (str "README.md has no TOML block right after <!-- " marker " -->") {})))))

(defn- command [config scope template]
  (spec/command (spec/spec config ["ruby-lsp" scope] "/r" "/cache") template {:backend-id "heddle-1"}))

(deftest example-runners-parse
  (let [c (config/parse (str (toml-after "example: runners") "
[servers.ruby-lsp]
languages = ['ruby']
boot = { command = ['ruby-lsp'] }

[scopes.a.ruby-lsp]
runner = 'compose'
service = 'api'
[scopes.b.ruby-lsp]
runner = 'docker-exec'
container = 'api-1'
[scopes.c.ruby-lsp]
runner = 'docker-run'
image = 'ruby:3.3'
"))]
    (is (= [] (config/warnings c)))
    (is (= ["docker" "compose" "exec" "-T" "--workdir" "/r/a" "api" "ruby-lsp"]
           (command c "a" "start")))
    (is (= ["docker" "exec" "--interactive" "--workdir" "/r/b" "api-1" "ruby-lsp"]
           (command c "b" "start")))
    (is (= ["docker" "run" "--rm" "--interactive" "--name" "heddle-1" "--workdir" "/r/c" "ruby:3.3" "ruby-lsp"]
           (command c "c" "start")))))

(deftest full-config-parses
  (let [c (config/parse (toml-after "example: full config"))]
    (is (= ["docker" "compose" "exec" "-T" "--workdir" "/app" "api" "bundle" "exec" "ruby-lsp"]
           (command c "services/api" "start")))))

(deftest examples-print-configs-that-expand-the-same
  (doseq [s [(toml-after "example: full config")
             (str (toml-after "example: full config") "\n" (toml-after "example: shared code"))
             (str "[servers.ruby-lsp]\nlanguages = ['ruby']\nboot = { command = ['bundle', 'exec', 'ruby-lsp'] }\n"
                  (toml-after "example: runner defaults"))]
          :let [[c again] (config-test/reprinted s)]]
    (is (= c again))
    (is (= [] (config/warnings again)))))

(deftest custom-runner-parses
  (let [c (config/parse (str (toml-after "example: full config") "\n"
                             (toml-after "example: custom runner")))]
    (is (= ["docker" "run" "--rm" "--detach" "--name" "heddle-1"
            "--volume" "/r:/src" "ruby:3.3" "sleep" "infinity"]
           (command c "tools/cli" "prepare")))
    (is (= ["docker" "exec" "--interactive" "--workdir" "/src/tools/cli" "heddle-1"
            "bundle" "exec" "ruby-lsp"]
           (command c "tools/cli" "start")))))

(deftest shared-code-parses
  (let [c (config/parse (str (toml-after "example: full config") "\n"
                             (toml-after "example: shared code")))]
    (is (= [] (config/warnings c)))
    (is (= ["services/billing" "services/orders"]
           (config/scopes-for c "ruby-lsp" "shared/analytics/lib/event.rb")))
    (is (= ["docker" "compose" "exec" "-T" "--workdir" "/app" "orders" "bundle" "exec" "ruby-lsp"]
           (command c "services/orders" "start")))))

(deftest runner-defaults-parse
  (let [c (config/parse (str "[servers.ruby-lsp]\nlanguages = ['ruby']\nboot = { command = ['bundle', 'exec', 'ruby-lsp'] }\n"
                             (toml-after "example: runner defaults")))]
    (is (= [] (config/warnings c)))
    (is (= ["docker" "compose" "--file" "dev/compose.yml" "exec" "-T" "--workdir" "/src/services/billing"
            "billing" "bundle" "exec" "ruby-lsp"]
           (command c "services/billing" "start")))))
