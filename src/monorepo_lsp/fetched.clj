(ns monorepo-lsp.fetched
  "Copies of fetched files on the host. See \"Terms\" and \"Paths\" in
  DESIGN.md."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [monorepo-lsp.config :as config]
            [monorepo-lsp.paths :as paths])
  (:import (java.io File)
           (java.net URLEncoder)
           (java.util UUID)
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

(defn cache-root
  "The directory that the config's `cache` table chooses for the cache, under
  :root. `repo` is the directory holding the config, which a relative `path` is
  resolved against, and `getenv` looks up an environment variable.

  If `env` names a variable that is unset, empty or relative, the cache goes in
  the system's temp directory instead, and :warning says why."
  [config repo getenv]
  (let [{:strs [env path]} (get config "cache")
        tmpdir (System/getProperty "java.io.tmpdir")]
    (cond
      path {:root (if (str/starts-with? path "/") path (paths/client-path repo path))}
      (nil? env) {:root tmpdir}
      :else
      (let [value (getenv env)
            unusable (cond (nil? value) "isn't set"
                           (= "" value) "is empty"
                           (not (str/starts-with? value "/")) (str "is a relative path, " (pr-str value)))]
        (if unusable
          {:root tmpdir
           :warning (str "The config's cache names $" env ", which " unusable ". "
                         "Fetched files are cached in " tmpdir " instead.")}
          {:root value})))))

(defn cache-dir
  "The directory under the cache's `root` holding fetched files for
  `server-type` in `scope`, as a client path. Every backend for them uses the
  same one, in any process."
  [root repo server-type scope]
  (str (str/replace root #"/+$" "")
       "/monorepo-lsp/" (repo-key repo) "/" (segment server-type) "/" (segment scope)))

(defn- read-into
  "Runs `command` in `dir` and writes its stdout to `target`, which is only
  created or replaced if the command succeeds. Returns nil, or why it failed.

  Since other proxies may be reading or fetching `target` at the same time,
  each call writes to a `.part` file of its own and renames it over `target`."
  [command dir ^File target]
  (let [part (io/file (str target "." (subs (str (UUID/randomUUID)) 0 8) ".part"))]
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

(defn- fetch-one!
  "Copies `file` into the backend's cache and records it as fetched. Returns
  nil, or why it couldn't."
  [{:keys [config server-type scope repo cache fetched] :as backend} file]
  (let [command (config/command config server-type scope "read"
                                (assoc (select-keys backend [:repo :backend-id]) :file file))
        problem (if command
                  (read-into command (paths/client-path repo scope) (io/file (str cache file)))
                  "the runner has no read template")]
    (when-not problem
      (swap! fetched conj file))
    problem))

(defn fetch!
  "Copies each of `files`, as server paths, into the backend's cache unless the
  backend has copied it already. `backend` has the :config, :server-type and
  :scope it was started for, the repo root as a client path under :repo, its
  :backend-id, its :cache directory, and the set of files it has :fetched in
  an atom.

  Since the cache outlives the backend, a copy left by an earlier one is
  replaced in case the file has changed. If it can't be replaced, it stays as
  it was.

  The runner's `read` command runs in the scope's directory, as `start` does.
  Returns a map of each file that couldn't be copied to why."
  [{:keys [fetched] :as backend} files]
  (into {}
        (for [file files
              :when (not (contains? @fetched file))
              :let [problem (fetch-one! backend file)]
              :when problem]
          [file problem])))
