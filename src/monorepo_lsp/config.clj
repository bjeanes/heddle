(ns monorepo-lsp.config
  "Reads `.monorepo-lsp.toml` and answers which server types handle a file and
  how each is started. Keys stay strings, as in the TOML. See DESIGN.md for the
  meaning of server types, scopes and boot configs."
  (:require [clj-toml.core :as toml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [monorepo-lsp.glob :as glob]
            [monorepo-lsp.paths :as paths]
            [monorepo-lsp.runner :as runner]))

(def file-name ".monorepo-lsp.toml")

(defn- invalid [source errors]
  (throw (ex-info (str/join "\n" (cons (str "invalid config in " source ":")
                                       (map #(str "  " %) errors)))
                  {:source source :errors (vec errors)})))

(defn- parse-toml [source s]
  (try
    (toml/parse-string s)
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [line column]} (ex-data e)]
        (if line
          (invalid source [(str "TOML syntax error at line " line ", column " column)])
          (throw e))))))

(defn- string-list? [v]
  (and (vector? v) (every? string? v)))

(defn- server-errors [name server]
  (let [where (str "server type " (pr-str name))]
    (if-not (map? server)
      [(str where " must be a table")]
      (concat
       (for [k (keys server)
             :when (not (#{"languages" "globs" "priority" "boot"} k))]
         (str where ": unknown key " (pr-str k)))
       (for [k ["languages" "globs"]
             :when (and (contains? server k) (not (string-list? (get server k))))]
         (str where ": " k " must be a list of strings"))
       (when-not (or (contains? server "languages") (contains? server "globs"))
         [(str where " matches no files; give it languages or globs")])
       (when-not (map? (get server "boot" {}))
         [(str where ": boot must be a table")])
       (when-not (integer? (get server "priority" 0))
         [(str where ": priority must be an integer")])))))

(defn- scope-errors [servers dir entries]
  (let [where (str "scope " (pr-str dir))]
    (cond
      (not (paths/relative-dir? dir))
      [(str where " must be a directory relative to the repo root, such as \"services/api\" or \".\"")]

      (not (map? entries))
      [(str where " must be a table")]

      :else
      (for [[server-type boot] entries
            :let [problem (cond (not (contains? servers server-type)) "is not a server type"
                                (not (map? boot)) "must be a table")]
            :when problem]
        (str where ", server type " (pr-str server-type) " " problem)))))

(defn- cache-errors [cache]
  (if-not (map? cache)
    ["cache must be a table, such as { env = \"XDG_CACHE_HOME\" }"]
    (let [unknown (remove #{"env" "path"} (keys cache))
          {:strs [env path]} cache]
      (cond
        (seq unknown)
        (for [k unknown] (str "cache: unknown key " (pr-str k)))

        (= (nil? env) (nil? path))
        ["cache needs either env or path, but not both"]

        env
        (when-not (and (string? env) (seq env))
          ["cache: env must be the name of an environment variable"])

        (not (string? path))
        ["cache: path must be a string"]

        (str/starts-with? path "~")
        ["cache: path can't start with ~, which isn't expanded; use env or an absolute path"]

        (not (or (paths/absolute-dir? path) (paths/relative-dir? path)))
        ["cache: path must be absolute, or relative to the config's directory, with no empty, . or .. segments"]))))

(defn- structure-errors [config]
  (let [{:strs [servers scopes runners]} config]
    (concat
     (for [k (keys config)
           :when (not (#{"servers" "scopes" "runners" "cache"} k))]
       (str "unknown section " (pr-str k)))
     (when (contains? config "cache")
       (cache-errors (get config "cache")))
     (for [k ["servers" "scopes" "runners"]
           :when (not (map? (get config k)))]
       (str k " must be a table"))
     (when (map? servers) (mapcat (fn [[k v]] (server-errors k v)) servers))
     (when (and (map? servers) (map? scopes))
       (mapcat (fn [[k v]] (scope-errors servers k v)) scopes))
     (when (map? runners)
       (for [[name runner] runners
             e (runner/runner-errors runner)]
         (str "runner " (pr-str name) ": " e))))))

(declare boot-config)

(defn- paths-errors [paths]
  (cond
    (nil? paths) nil
    (not (map? paths)) ["paths must be a table"]
    :else (for [[dir server] paths
                :let [problem (cond (not (paths/relative-dir? dir)) "must be relative to the repo root"
                                    (not (paths/absolute-dir? server)) "must map to an absolute directory")]
                :when problem]
            (str "paths entry " (pr-str dir) " " problem))))

(def ^:private stand-ins
  "Runtime values for checking templates before any backend is running."
  {:repo "/repo" :backend-id "id" :file "/file"})

(defn- boot-errors [config scope server-type]
  (let [boot (boot-config config server-type scope)
        runner-name (get boot "runner")
        runner (get-in config ["runners" runner-name])
        path-errors (paths-errors (get boot "paths"))]
    (cond
      (nil? runner-name) ["names no runner"]
      (not (string? runner-name)) ["runner must be a string"]
      (nil? runner) [(str "unknown runner " (pr-str runner-name))]
      (seq path-errors) path-errors
      :else
      (let [ctx (runner/context runner boot scope stand-ins)]
        (if-not (:server-scope ctx)
          [runner/uncovered-scope]
          (for [t runner/templates
                :when (contains? runner t)
                e (second (runner/expand* runner t ctx))]
            (str "runner " (pr-str runner-name) ", " t ": " e)))))))

(defn- scope-runner-errors [config]
  (for [[scope entries] (get config "scopes")
        server-type (keys entries)
        e (boot-errors config scope server-type)]
    (str "scope " (pr-str scope) ", server type " (pr-str server-type) ": " e)))

(defn- checked [config source find-errors]
  (let [errors (find-errors config)]
    (when (seq errors) (invalid source errors))
    config))

(defn parse
  "Parses and checks config from a TOML string, with the built-in runners added.
  Throws ex-info listing every problem found, under :errors. `source` names the
  file in that message."
  ([s] (parse s file-name))
  ([s source]
   (-> (merge {"servers" {} "scopes" {} "runners" {}} (parse-toml source s))
       (checked source structure-errors)
       (update "runners" #(merge runner/builtins %))
       (checked source scope-runner-errors))))

(defn read-config
  "Reads the config at the root of the repo at `root`, or returns nil if it has
  none."
  [root]
  (let [f (io/file root file-name)]
    (when (.exists f)
      (parse (slurp f) (str f)))))

(defn warnings
  "Values in a valid config's scopes that the scope's runner never uses. A
  server type's boot table can set a value for one runner that a scope then
  swaps for another, so these aren't errors."
  [config]
  (for [[scope entries] (get config "scopes")
        server-type (keys entries)
        :let [boot (boot-config config server-type scope)
              runner-name (get boot "runner")]
        k (runner/unused-values (get-in config ["runners" runner-name]) boot)]
    (str "scope " (pr-str scope) ", server type " (pr-str server-type) ": "
         (pr-str k) " isn't used by runner " (pr-str runner-name))))

(defn warnings-message
  "The `warnings` for the config file at `source`, as one message."
  [source warnings]
  (str/join "\n" (cons (str "unused values in " source ":")
                       (map #(str "  " %) warnings))))

(defn server-types
  "The server types that handle the file at the repo-relative `path`, which the
  client opened as `language-id` (nil if unknown)."
  [config path language-id]
  (set (for [[name server] (get config "servers")
             :when (or (some #{language-id} (get server "languages"))
                       (some #(glob/matches? % path) (get server "globs")))]
         name)))

(defn languages
  "The languages, sorted, of every server type that some scope starts."
  [config]
  (let [started (set (mapcat keys (vals (get config "scopes"))))]
    (vec (sort (distinct (mapcat #(get-in config ["servers" % "languages"]) started))))))

(defn by-priority
  "The server types in the order they're asked a request that wants one
  answer: highest `priority` first, then by name. A server type's priority is
  0 unless it sets one."
  [config server-types]
  (sort-by (juxt #(- (get-in config ["servers" % "priority"] 0)) identity)
           server-types))

(defn- depth [dir]
  (if (= "." dir) 0 (count (str/split dir #"/"))))

(defn scope-for
  "The scope that starts `server-type` for the repo-relative `path`, or nil if
  none does."
  [config server-type path]
  (let [dirs (for [[dir entries] (get config "scopes")
                   :when (and (contains? entries server-type) (paths/within? dir path))]
               dir)]
    (when (seq dirs)
      (apply max-key depth dirs))))

(defn boot-config
  "The server type's `boot` table with the scope's values over the top."
  [config server-type scope]
  (merge (get-in config ["servers" server-type "boot"])
         (get-in config ["scopes" scope server-type])))

(defn server-scope
  "The scope's directory as a server path, which is also the backend's root.
  `repo` is the repo root as a client path."
  [config server-type scope repo]
  (let [boot (boot-config config server-type scope)]
    (paths/to-server (get boot "paths") repo (paths/client-path repo scope))))

(defn command
  "The arguments for the runner's `template` (\"start\", \"read\" or
  \"prepare\") for a server type in a scope, or nil if the runner has no such
  template. `runtime` has the repo root as a client path under :repo, the
  :backend-id and, for `read`, the server path of the :file to print."
  [config server-type scope template runtime]
  (let [boot (boot-config config server-type scope)
        runner (get-in config ["runners" (get boot "runner")])]
    (runner/expand runner template (runner/context runner boot scope runtime))))
