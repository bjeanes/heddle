(ns heddle.fetched
  "Copies of fetched files on the host. See \"Terms\" and \"Paths\" in
  DESIGN.md."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [heddle.log :as log]
            [heddle.paths :as paths]
            [heddle.process :as process]
            [heddle.spec :as spec]
            [heddle.translate :as translate])
  (:import (java.io File)
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

(defn- unusable
  "Why `value`, read from an environment variable, can't be the cache root, or
  nil if it can."
  [value]
  (cond (nil? value) "isn't set"
        (= "" value) "is empty"
        (not (str/starts-with? value "/")) (str "is a relative path, " (pr-str value))))

(defn- chosen-root
  "The directory that the config's `cache` table chooses, under :root. `repo`
  is the directory holding the config, which a relative `path` is resolved
  against. `getenv` looks up an environment variable.

  If `env` names a variable that is unset, empty or relative, or `xdg` finds
  neither $XDG_CACHE_HOME nor $HOME usable, the cache goes in the system's temp
  directory instead. :warning then says why."
  [config repo getenv]
  (let [{:strs [env path xdg]} (get config "cache")
        tmpdir (System/getProperty "java.io.tmpdir")
        fall-back (fn [why]
                    {:root tmpdir
                     :warning (str "The config's cache " why ". "
                                   "Fetched files are cached in " tmpdir " instead.")})]
    (cond
      path {:root (if (str/starts-with? path "/") path (paths/client-path repo path))}

      xdg
      (let [cache-home (getenv "XDG_CACHE_HOME")
            home (getenv "HOME")]
        (cond
          (not (unusable cache-home)) {:root cache-home}
          (not (unusable home)) {:root (str (str/replace home #"/+$" "") "/.cache")}
          :else (fall-back (str "follows XDG, but $XDG_CACHE_HOME " (unusable cache-home)
                                " and $HOME " (unusable home)))))

      env
      (let [value (getenv env)]
        (if-let [problem (unusable value)]
          (fall-back (str "names $" env ", which " problem))
          {:root value}))

      :else {:root tmpdir})))

(defn- repo-dir
  "The repo's own directory in `root`, which other repos' caches may share."
  [root repo]
  (str (str/replace root #"/+$" "") "/heddle/" (repo-key repo)))

(defn cache-root
  "The directory holding the repo's cache under :root, and any :warning about
  the config's `cache` table, as `chosen-root` describes.

  A `path` is used as given, since it's only in this repo's config. Since the
  temp directory, `xdg` and `env` can each name a directory that other repos
  use too, the repo's cache is `heddle/<repo>-<hash>` inside it."
  [config repo getenv]
  (cond-> (chosen-root config repo getenv)
    (not (get-in config ["cache" "path"])) (update :root repo-dir repo)))

(defn- read-into
  "Runs `command` in `dir` and writes its stdout to `target`, which is only
  created or replaced if the command succeeds within `timeout-ms`. Returns nil,
  or why it failed.

  Since other proxies may be reading or fetching `target` at the same time,
  each call writes to a `.part` file of its own and renames it over `target`."
  [command dir ^File target timeout-ms]
  (let [part (io/file (str target "." (subs (str (UUID/randomUUID)) 0 8) ".part"))]
    (try
      (.mkdirs (.getParentFile target))
      (let [{:keys [exit timed-out output]} (process/run command dir {:stdout part :timeout-ms timeout-ms})]
        (cond
          timed-out
          (str (pr-str command) " didn't finish within " (process/duration timeout-ms))

          (not (zero? exit))
          (str (pr-str command) " exited with " exit
               (when-not (str/blank? output) (str ": " (str/trim output))))

          (not (and (.setReadOnly part) (.renameTo part target)))
          (str "couldn't move " part " into place")))
      (catch Exception e
        (ex-message e))
      (finally
        (.delete part)))))

(defn- fetch-one!
  "Copies `file` into the backend's cache and records it as refreshed. Returns
  nil, or why it couldn't."
  [{:keys! [spec backend-id refreshed read-timeout-ms]} file]
  (let [{:keys! [dir cache]} spec
        command (spec/command spec "read" {:backend-id backend-id :file file})
        problem (if command
                  (read-into command dir (io/file (str cache file)) read-timeout-ms)
                  "the runner has no read template")]
    (when-not problem
      (swap! refreshed conj file))
    problem))

(defn fetch!
  "Copies each of `files`, as server paths, into the backend's cache unless the
  backend has copied it already. `backend` has its :spec (see heddle.spec), its
  :backend-id, an atom of the set of files it has copied under :refreshed, and
  how long each `read` may take in :read-timeout-ms.

  Since the cache outlives the backend, a copy left by an earlier one is
  replaced in case the file has changed. If it can't be replaced, it stays as
  it was.

  The runner's `read` command runs in the scope's directory, as `start` does.
  Returns a map of each file that couldn't be copied to why."
  [{:keys [refreshed] :as backend} files]
  (into {}
        (for [file files
              :when (not (contains? @refreshed file))
              :let [problem (fetch-one! backend file)]
              :when problem]
          [file problem])))

(defn to-client!
  "Translates the payload `v` from the backend `b` for the client, after
  fetching any files it refers to that only the backend can see. If a file
  can't be fetched, it is logged. The payload still refers to its copy in the
  cache."
  [{:keys! [spec backend-id] :as b} v]
  (doseq [[file problem] (fetch! b (translate/fetched-files spec v))]
    (log/info "couldn't fetch" file "from" backend-id "-" problem))
  (translate/to-client spec v))
