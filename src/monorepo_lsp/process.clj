(ns monorepo-lsp.process
  "Commands that run to completion, such as a runner's `prepare` and `read`.
  A backend's `start` is a long-lived peer instead. See monorepo-lsp.backend."
  (:require [clojure.java.io :as io])
  (:import (java.io File)
           (java.util.concurrent TimeUnit)))

(def ^:private kill-grace-ms 2000)

(defn kill!
  "Asks the process `proc`, and every process it started, to stop. Once `proc`
  has stopped, or `kill-grace-ms` have passed, forces any still running.

  A wrapper such as `sh -c` or `docker compose` runs the real work in a child,
  which would otherwise outlive it and keep its output open."
  [^Process proc]
  (let [descendants (vec (iterator-seq (.iterator (.descendants (.toHandle proc)))))]
    (run! #(.destroy ^ProcessHandle %) descendants)
    (.destroy proc)
    (.waitFor proc kill-grace-ms TimeUnit/MILLISECONDS)
    (run! #(.destroyForcibly ^ProcessHandle %) (filter #(.isAlive ^ProcessHandle %) descendants))
    (when (.isAlive proc)
      (.destroyForcibly proc)
      (.waitFor proc kill-grace-ms TimeUnit/MILLISECONDS))))

(defn duration
  "`ms` as a person would write it: in whole seconds, unless it's under 10 s
  and that would round it."
  [ms]
  (if (or (zero? (rem ms 1000)) (<= 10000 ms))
    (str (quot (+ ms 500) 1000) " s")
    (str ms " ms")))

(defn run
  "Runs `command` in the directory `dir` until it exits, or kills it once
  `opts` :timeout-ms have passed. If `opts` has a :stdout file, the command's
  stdout goes to it and :output is its stderr. Otherwise, :output is its
  stdout and stderr combined. Returns its :exit code, or :timed-out true, and
  its :output."
  [command dir {:keys [^File stdout timeout-ms]}]
  (let [proc (.start (cond-> (doto (ProcessBuilder. ^java.util.List command)
                               (.directory (io/file dir)))
                       stdout (.redirectOutput stdout)
                       (nil? stdout) (.redirectErrorStream true)))
        output (future (slurp (if stdout (.getErrorStream proc) (.getInputStream proc))))
        exited? (.waitFor proc timeout-ms TimeUnit/MILLISECONDS)]
    (when-not exited?
      (kill! proc))
    ;; A process the command started can keep its output open after it exits.
    (merge {:output (deref output kill-grace-ms "")}
           (if exited?
             {:exit (.exitValue proc)}
             {:timed-out true}))))
