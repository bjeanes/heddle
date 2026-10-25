(ns monorepo-lsp.readme-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [monorepo-lsp.config :as config]
            [monorepo-lsp.runner :as runner]))

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
  (is (= runner/builtin-toml (toml-after "resources/monorepo_lsp/runners.toml"))))

(def ^:private runtime {:repo "/r" :backend-id "monorepo-lsp-1"})

(deftest full-config-parses
  (let [c (config/parse (toml-after "example: full config"))]
    (is (= ["docker" "compose" "exec" "-T" "--workdir" "/app" "api" "bundle" "exec" "ruby-lsp"]
           (config/command c "ruby-lsp" "services/api" "start" runtime)))))

(deftest custom-runner-parses
  (let [c (config/parse (str (toml-after "example: full config") "\n"
                             (toml-after "example: custom runner")))]
    (is (= ["nix" "develop" "/r" "--command" "true"]
           (config/command c "ruby-lsp" "tools/cli" "prepare" runtime)))
    (is (= ["nix" "develop" "/r" "--command" "bundle" "exec" "ruby-lsp"]
           (config/command c "ruby-lsp" "tools/cli" "start" runtime)))))
