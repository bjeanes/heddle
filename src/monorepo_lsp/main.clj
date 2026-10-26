(ns monorepo-lsp.main
  (:require [monorepo-lsp.lsp :as lsp])
  (:import (java.io BufferedInputStream InputStream OutputStream)))

(defn- pump
  "Copies messages from `in` to `out` until `in` ends, parsing and re-encoding
  each one on the way."
  [^InputStream in ^OutputStream out]
  (loop []
    (when-let [msg (lsp/read-message in)]
      (lsp/write-message out msg)
      (recur))))

(defn- passthrough
  "Starts `command` and relays messages between it and this process's stdio.
  Returns the command's exit code."
  [command]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec command))
             (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT))
        proc (.start pb)
        server-in (.getOutputStream proc)]
    (future
      (try
        (pump (BufferedInputStream. System/in) server-in)
        (finally (.close server-in))))
    (pump (BufferedInputStream. (.getInputStream proc)) System/out)
    (.waitFor proc)))

(defn -main [& args]
  (let [command (if (= "--" (first args)) (rest args) args)]
    (if (empty? command)
      (do (binding [*out* *err*]
            (println "usage: monorepo-lsp [--] COMMAND [ARG...]"))
          (System/exit 2))
      (System/exit (passthrough command)))))
