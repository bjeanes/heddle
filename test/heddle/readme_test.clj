(ns heddle.readme-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [heddle.config :as config]
            [heddle.runner :as runner]))

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

(deftest built-in-runners-match-resource
  (is (= runner/builtin-toml (toml-after "resources/heddle/runners.toml"))))

(def ^:private runtime {:repo "/r" :backend-id "heddle-1"})

(deftest full-config-parses
  (let [c (config/parse (toml-after "example: full config"))]
    (is (= ["docker" "compose" "exec" "-T" "--workdir" "/app" "api" "bundle" "exec" "ruby-lsp"]
           (config/command c "ruby-lsp" "services/api" "start" runtime)))))

(deftest custom-runner-parses
  (let [c (config/parse (str (toml-after "example: full config") "\n"
                             (toml-after "example: custom runner")))]
    (is (= ["docker" "run" "--rm" "--detach" "--name" "heddle-1"
            "--volume" "/r:/src" "ruby:3.3" "sleep" "infinity"]
           (config/command c "ruby-lsp" "tools/cli" "prepare" runtime)))
    (is (= ["docker" "exec" "--interactive" "--workdir" "/src/tools/cli" "heddle-1"
            "bundle" "exec" "ruby-lsp"]
           (config/command c "ruby-lsp" "tools/cli" "start" runtime)))))
