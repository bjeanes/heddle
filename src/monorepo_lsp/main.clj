(ns monorepo-lsp.main
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [monorepo-lsp.backend :as backend]
            [monorepo-lsp.config :as config]
            [monorepo-lsp.json :as json]
            [monorepo-lsp.log :as log]
            [monorepo-lsp.lsp :as lsp]
            [monorepo-lsp.proxy :as proxy])
  (:import (java.io BufferedInputStream)))

(def ^:private usage
  "usage: monorepo-lsp [--eager] [SERVER-TYPE...]
       monorepo-lsp --languages [DIR]

Serves every server type in the config, or only those named.

--eager starts every backend when the client initializes, and waits up to
30 s for them before answering, so that the answer lists their features and
commands. Otherwise each backend starts when one of its files opens.

--languages prints, as JSON, the languages that the config at the root of DIR
covers. DIR is the current directory unless given.")

(defn languages
  "What `monorepo-lsp --languages` prints for the repo at `dir`, as :out on success
  or :err on failure, with its :exit status. :err also has any warnings about
  the config."
  [dir]
  (try
    (if-let [c (config/read-config dir)]
      (let [warnings (config/warnings c)]
        (cond-> {:exit 0 :out (json/write-str {"languages" (config/languages c)})}
          (seq warnings) (assoc :err (config/warnings-message (str (io/file dir config/file-name)) warnings))))
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
    (when err
      (binding [*out* *err*] (println err)))
    (when out
      (println out))
    (flush)
    (System/exit exit)))

(defn- handle! [proxy msg]
  (try
    (proxy/handle-client! proxy msg)
    (catch Exception e
      (log/info "couldn't handle" (get msg "method" "a response") "-" (ex-message e))
      (when (and (contains? msg "id") (contains? msg "method"))
        (lsp/write-message System/out (lsp/error-response (get msg "id") -32603 (ex-message e)))))))

(defn- serve! [eager? server-types]
  (let [proxy (proxy/create {:send #(lsp/write-message System/out %)
                             :connect backend/connect-process
                             :server-types (when (seq server-types) (set server-types))
                             :eager eager?
                             :cwd (System/getProperty "user.dir")
                             :exit #(System/exit %)})
        in (BufferedInputStream. System/in)]
    (loop []
      (if-let [msg (lsp/read-message in)]
        (do (handle! proxy msg)
            (recur))
        (handle! proxy (lsp/notification "exit" nil))))))

(defn -main [& args]
  (let [eager? (= "--eager" (first args))
        args (if eager? (rest args) args)]
    (when (= "--languages" (first args))
      (languages! (rest args)))
    (when (some #(str/starts-with? % "-") args)
      (usage!))
    (serve! eager? args)))
