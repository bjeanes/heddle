(ns monorepo-lsp.fetched
  "Copies of fetched files on the host. See \"Terms\" and \"Paths\" in
  DESIGN.md."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [monorepo-lsp.config :as config]
            [monorepo-lsp.paths :as paths])
  (:import (java.io File)
           (java.net URLEncoder)
           (java.util.zip CRC32)))

(defn- repo-key
  "The repo's directory name and a hash of its path, which tells apart repos
  with the same name."
  [repo]
  (let [file (.getAbsoluteFile (io/file repo))
        crc (CRC32.)]
    (.update crc (.getBytes (str file) "UTF-8"))
    (str (.getName file) "-" (format "%08x" (.getValue crc)))))

(defn- segment
  "`s` percent-encoded as one path segment. Since `.` and `..` can't name a
  directory of their own, their dots are encoded too, which URLEncoder doesn't
  do. The root scope `.` becomes `%2E`."
  [s]
  (let [encoded (URLEncoder/encode ^String s "UTF-8")]
    (if (re-matches #"\.+" encoded)
      (str/replace encoded "." "%2E")
      encoded)))

(defn cache-dir
  "The directory holding fetched files for `server-type` in `scope`, as a
  client path. Every backend for them uses the same one, in any process."
  [repo server-type scope]
  (str (str/replace (System/getProperty "java.io.tmpdir") #"/+$" "")
       "/monorepo-lsp/" (repo-key repo) "/" (segment server-type) "/" (segment scope)))

(defn- read-into
  "Runs `command` in `dir` and writes its stdout to `target`, which is only
  created if the command succeeds. Returns nil, or why it failed."
  [command dir ^File target]
  (let [part (io/file (str target ".part"))]
    (try
      (.mkdirs (.getParentFile target))
      (let [proc (.start (doto (ProcessBuilder. ^java.util.List command)
                           (.directory (io/file dir))
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

  The runner's `read` command runs in the scope's directory, as `start` does.
  Returns a map of each file that couldn't be copied to why."
  [{:keys [config server-type scope repo cache] :as backend} files]
  (into {}
        (for [file files
              :let [target (io/file (str cache file))]
              :when (not (.exists target))
              :let [command (config/command config server-type scope "read"
                                            (assoc (select-keys backend [:repo :backend-id]) :file file))
                    problem (if command
                              (read-into command (paths/client-path repo scope) target)
                              "the runner has no read template")]
              :when problem]
          [file problem])))
