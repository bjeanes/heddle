(ns monorepo-lsp.main
  (:require [clojure.string :as str]
            [monorepo-lsp.backend :as backend]
            [monorepo-lsp.log :as log]
            [monorepo-lsp.lsp :as lsp]
            [monorepo-lsp.proxy :as proxy])
  (:import (java.io BufferedInputStream)))

(def ^:private usage
  "usage: monorepo-lsp [SERVER-TYPE...]

Serves every server type in the config, or only those named.")

(defn- handle! [proxy msg]
  (try
    (proxy/handle-client! proxy msg)
    (catch Exception e
      (log/info "couldn't handle" (get msg "method" "a response") "-" (ex-message e))
      (when (and (contains? msg "id") (contains? msg "method"))
        (lsp/write-message System/out (lsp/error-response (get msg "id") -32603 (ex-message e)))))))

(defn -main [& server-types]
  (when (some #(str/starts-with? % "-") server-types)
    (binding [*out* *err*] (println usage))
    (System/exit 2))
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
