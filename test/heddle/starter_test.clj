(ns heddle.starter-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [heddle.config :as config]
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

(def ^:private runtime {:repo "/r" :backend-id "heddle-1"})

(deftest every-example-loads-once-uncommented
  (let [c (config/parse (uncomment))]
    (is (= [] (config/warnings c)))
    (is (= {"xdg" true} (get c "cache")))
    (is (= ["typescript-language-server" "--stdio"]
           (config/command c "ts-ls" "." "start" runtime)))
    (is (= ["docker" "compose" "exec" "-T" "--workdir" "/app" "web" "typescript-language-server" "--stdio"]
           (config/command c "ts-ls" "apps/web" "start" runtime)))
    (is (= ["docker" "run" "--rm" "--detach" "--name" "heddle-1" "--volume" "/r:/src"
            "my-app-dev" "sleep" "infinity"]
           (config/command c "ts-ls" "tools/cli" "prepare" runtime)))
    (is (= ["docker" "exec" "--interactive" "--workdir" "/src/tools/cli" "heddle-1"
            "typescript-language-server" "--stdio"]
           (config/command c "ts-ls" "tools/cli" "start" runtime)))))

(deftest each-scope-loads-with-only-the-server-type
  (doseq [[scope header] {"." "# [scopes.\".\"]"
                          "apps/web" "# [scopes.\"apps/web\""
                          "tools/cli" "# [runners.docker-detached]"}]
    (let [c (config/parse (uncomment "# [servers.ts-ls]" header))]
      (is (= [] (config/warnings c)) scope)
      (is (= [scope] (keys (get c "scopes")))))))
