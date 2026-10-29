;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.starter-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [heddle.config :as config]
            [heddle.config-test :as config-test]
            [heddle.spec :as spec]
            [heddle.starter :as starter]))

(defn- uncomment-examples [paragraph]
  (str/join "\n" (for [line (str/split-lines paragraph)]
                   (if (str/starts-with? line "# ") (subs line 2) line))))

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

(defn- command [config scope template]
  (spec/command (spec/spec config ["ts-ls" scope] "/r" "/cache") template {:backend-id "heddle-1"}))

(deftest every-example-loads-once-uncommented
  (let [c (config/parse (uncomment))]
    (is (= [] (config/warnings c)))
    (is (= {"xdg" true} (get c "cache")))
    (is (= {"path" "heddle-trace.jsonl"} (get c "trace")))
    (is (= ["typescript-language-server" "--stdio"]
           (command c "." "start")))
    (is (= ["docker" "compose" "exec" "-T" "--workdir" "/app" "web" "typescript-language-server" "--stdio"]
           (command c "apps/web" "start")))
    (is (= ["docker" "run" "--rm" "--detach" "--name" "heddle-1" "--volume" "/r:/src"
            "my-app-dev" "sleep" "infinity"]
           (command c "tools/cli" "prepare")))
    (is (= ["docker" "exec" "--interactive" "--workdir" "/src/tools/cli" "heddle-1"
            "typescript-language-server" "--stdio"]
           (command c "tools/cli" "start")))))

(deftest every-example-prints-a-config-that-expands-the-same
  (doseq [s [(uncomment) (uncomment "# [servers.ts-ls]")]
          :let [[c again] (config-test/reprinted s)]]
    (is (= c again))
    (is (= [] (config/warnings again)))))

(deftest the-server-type-alone-runs-across-the-repo
  (let [c (config/parse (uncomment "# [servers.ts-ls]"))]
    (is (= [] (config/warnings c)))
    (is (config/implicit? c ["ts-ls" "."]))
    (is (= ["typescript-language-server" "--stdio"] (command c "." "start")))))

(deftest each-scope-loads-with-only-the-server-type
  (doseq [[scope header] {"." "# [scopes.\".\".ts-ls]"
                          "apps/web" "# [scopes.\"apps/web\""
                          "tools/cli" "# [runners.docker-detached]"}]
    (let [c (config/parse (uncomment "# [servers.ts-ls]" header))]
      (is (= [] (config/warnings c)) scope)
      (is (= [scope] (keys (get c "scopes")))))))
