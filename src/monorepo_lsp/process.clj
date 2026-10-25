(ns monorepo-lsp.process
  "Commands that run to completion, such as a runner's `prepare` and `read`.
  A backend's `start` is a long-lived peer instead. See monorepo-lsp.backend."
  (:require [clojure.java.io :as io])
  (:import (java.io File)))

(defn run
  "Runs `command` in the directory `dir` until it exits. If `opts` has a
  :stdout file, the command's stdout goes to it and :output is its stderr.
  Otherwise, :output is its stdout and stderr combined. Returns its :exit code
  and :output."
  [command dir {:keys [^File stdout]}]
  (let [proc (.start (cond-> (doto (ProcessBuilder. ^java.util.List command)
                               (.directory (io/file dir)))
                       stdout (.redirectOutput stdout)
                       (nil? stdout) (.redirectErrorStream true)))
        output (slurp (if stdout (.getErrorStream proc) (.getInputStream proc)))]
    {:exit (.waitFor proc)
     :output output}))
