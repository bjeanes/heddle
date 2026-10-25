(ns monorepo-lsp.main
  (:require [clojure.string :as str]
            [monorepo-lsp.backend :as backend]
            [monorepo-lsp.config :as config]
            [monorepo-lsp.json :as json]
            [monorepo-lsp.log :as log]
            [monorepo-lsp.lsp :as lsp]
            [monorepo-lsp.proxy :as proxy])
  (:import (java.io BufferedInputStream)))

(def ^:private usage
  "usage: monorepo-lsp [SERVER-TYPE...]
       monorepo-lsp --languages [DIR]

Serves every server type in the config, or only those named.

--languages prints, as JSON, the languages that the config at the root of DIR
covers. DIR is the current directory unless given.")

(defn languages
  "What `monorepo-lsp --languages` prints for the repo at `dir`, as :out on success
  or :err on failure, with its :exit status."
  [dir]
  (try
    (if-let [c (config/read-config dir)]
      {:exit 0 :out (json/write-str {"languages" (config/languages c)})}
      {:exit 1 :err (str "There's no " config/file-name " in " dir ".")})
    (catch Exception e
      {:exit 1 :err (ex-message e)})))

(defn- usage! []
  (binding [*out* *err*] (println usage))
  (System/exit 2))

(defn- languages! [[dir & more]]
  (when (seq more)
    (usage!))
  (let [{:keys [exit out err]} (languages (or dir (System/getProperty "user.dir")))]
    (if out
      (println out)
      (binding [*out* *err*] (println err)))
    (flush)
    (System/exit exit)))

(defn- handle! [proxy msg]
  (try
    (proxy/handle-client! proxy msg)
    (catch Exception e
      (log/info "couldn't handle" (get msg "method" "a response") "-" (ex-message e))
      (when (and (contains? msg "id") (contains? msg "method"))
        (lsp/write-message System/out (lsp/error-response (get msg "id") -32603 (ex-message e)))))))

(defn -main [& server-types]
  (when (= "--languages" (first server-types))
    (languages! (rest server-types)))
  (when (some #(str/starts-with? % "-") server-types)
    (usage!))
  (let [proxy (proxy/create {:send #(lsp/write-message System/out %)
                             :connect backend/connect-process
                             :server-types (when (seq server-types) (set server-types))
                             :cwd (System/getProperty "user.dir")
                             :exit #(System/exit %)})
        in (BufferedInputStream. System/in)]
    (loop []
      (if-let [msg (lsp/read-message in)]
        (do (handle! proxy msg)
            (recur))
        (handle! proxy (lsp/notification "exit" nil))))))
