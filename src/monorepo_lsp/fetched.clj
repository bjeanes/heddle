(ns monorepo-lsp.fetched
  "Copies of fetched files on the host. See \"Terms\" and \"Paths\" in
  DESIGN.md."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [monorepo-lsp.config :as config])
  (:import (java.io File)))

(defn cache-dir
  "The directory holding the backend's fetched files, as a client path."
  [backend-id]
  (str (str/replace (System/getProperty "java.io.tmpdir") #"/+$" "")
       "/monorepo-lsp/" backend-id))

(defn- read-into
  "Runs `command` and writes its stdout to `target`, which is only created if the
  command succeeds. Returns nil, or why it failed."
  [command ^File target]
  (let [part (io/file (str target ".part"))]
    (try
      (.mkdirs (.getParentFile target))
      (let [proc (.start (doto (ProcessBuilder. ^java.util.List command)
                           (.redirectOutput part)))
            stderr (slurp (.getErrorStream proc))
            exit (.waitFor proc)]
        (cond
          (not (zero? exit))
          (str (pr-str command) " exited with " exit
               (when-not (str/blank? stderr) (str ": " (str/trim stderr))))

          (not (and (.setReadOnly part) (.renameTo part target)))
          (str "couldn't move " part " into place")))
      (catch Exception e
        (ex-message e))
      (finally
        (.delete part)))))

(defn fetch!
  "Copies each of `files`, as server paths, into the backend's cache unless a
  copy is already there. `backend` has the :config, :server-type and :scope it
  was started for, the repo root as a client path under :repo, its
  :backend-id and its :cache directory.

  Returns a map of each file that couldn't be copied to why."
  [{:keys [config server-type scope cache] :as backend} files]
  (into {}
        (for [file files
              :let [target (io/file (str cache file))]
              :when (not (.exists target))
              :let [command (config/command config server-type scope "read"
                                            (assoc (select-keys backend [:repo :backend-id]) :file file))
                    problem (if command
                              (read-into command target)
                              "the runner has no read template")]
              :when problem]
          [file problem])))
