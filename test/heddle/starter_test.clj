(ns heddle.starter-test
  (:require [clj-toml.core :as toml]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [heddle.config :as config]
            [heddle.runner :as runner]
            [heddle.starter :as starter]))

(defn- uncomment-examples [paragraph]
  (str/join "\n" (for [line (str/split-lines paragraph)]
                   (if (re-find #"^#[^\s#]" line) (subs line 1) line))))

(defn- uncomment
  "The starter with the examples uncommented in each paragraph that has one of
  the example lines in `headers`, or in every paragraph if there are none."
  [& headers]
  (str/join "\n\n" (for [paragraph (str/split starter/toml #"\n\n")]
                     (if (or (empty? headers) (some #(str/includes? paragraph %) headers))
                       (uncomment-examples paragraph)
                       paragraph))))

(deftest loads-as-written-with-no-server-types
  (let [c (config/parse starter/toml)]
    (is (= {} (get c "servers")))
    (is (= {} (get c "scopes")))
    (is (= [] (config/warnings c)))))

(def ^:private runtime {:repo "/r" :backend-id "heddle-1"})

(deftest every-example-loads-once-uncommented
  (let [c (config/parse (uncomment))]
    (is (= [] (config/warnings c)))
    (is (= {"xdg" true} (get c "cache")))
    (is (= ["bundle" "exec" "ruby-lsp"]
           (config/command c "ruby-lsp" "." "start" runtime)))
    (is (= ["docker" "compose" "exec" "-T" "--workdir" "/app" "api" "bundle" "exec" "ruby-lsp"]
           (config/command c "ruby-lsp" "services/api" "start" runtime)))
    (is (= ["docker" "exec" "--interactive" "--workdir" "/app" "worker" "bundle" "exec" "ruby-lsp"]
           (config/command c "ruby-lsp" "services/worker" "start" runtime)))
    (is (= ["docker" "run" "--rm" "--interactive" "--name" "heddle-1" "--volume" "/r:/src"
            "--workdir" "/src/tools/cli" "ruby:3.3" "bundle" "exec" "ruby-lsp"]
           (config/command c "ruby-lsp" "tools/cli" "start" runtime)))))

(deftest each-scope-loads-with-only-the-server-type
  (doseq [[scope runner] {"." "host"
                          "services/api" "compose"
                          "services/worker" "docker-exec"
                          "tools/cli" "docker-run"}]
    (let [c (config/parse (uncomment "#[servers.ruby-lsp]" (str "#[scopes." (pr-str scope) "]")))]
      (is (= [] (config/warnings c)) scope)
      (is (= [scope] (keys (get c "scopes"))))
      (is (= runner (get-in c ["scopes" scope "ruby-lsp" "runner"]))))))

(deftest shows-the-built-in-runners-as-they-are
  (is (= runner/builtins
         (get (toml/parse-string (uncomment "#[runners.")) "runners"))))
