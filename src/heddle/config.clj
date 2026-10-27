(ns heddle.config
  "Reads `.heddle.toml` and answers which server types handle a file and
  how each is started. Keys stay strings, as in the TOML. See DESIGN.md for the
  meaning of server types, scopes and boot configs."
  (:require [clj-toml.core :as toml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [heddle.glob :as glob]
            [heddle.paths :as paths]
            [heddle.runner :as runner]
            [heddle.spec :as spec]))

(def file-name ".heddle.toml")

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

(defn- server-errors [name server]
  (let [where (str "server type " (pr-str name))]
    (if-not (map? server)
      [(str where " must be a table")]
      (concat
       (for [k (keys server)
             :when (not (#{"languages" "globs" "priority" "boot"} k))]
         (str where ": unknown key " (pr-str k)))
       (for [k ["languages" "globs"]
             :when (and (contains? server k) (not (runner/string-list? (get server k))))]
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
    ["cache must be a table, such as { xdg = true }"]
    (let [unknown (remove #{"env" "path" "xdg"} (keys cache))
          {:strs [env path xdg]} cache]
      (cond
        (seq unknown)
        (for [k unknown] (str "cache: unknown key " (pr-str k)))

        (not= 1 (count (remove nil? [env path xdg])))
        ["cache needs exactly one of env, path or xdg"]

        env
        (when-not (and (string? env) (seq env))
          ["cache: env must be the name of an environment variable"])

        (some? xdg)
        (when-not (true? xdg)
          ["cache: xdg must be true"])

        (not (string? path))
        ["cache: path must be a string"]

        (str/starts-with? path "~")
        ["cache: path can't start with ~, which isn't expanded; use xdg, env or an absolute path"]

        (not (or (paths/absolute-dir? path) (paths/relative-dir? path)))
        ["cache: path must be absolute, or relative to the config's directory, with no empty, . or .. segments"]))))

(defn- trace-errors [trace]
  (if-not (map? trace)
    ["trace must be a table, such as { path = \"trace.jsonl\" }"]
    (let [unknown (remove #{"path"} (keys trace))
          {:strs [path]} trace]
      (cond
        (seq unknown)
        (for [k unknown] (str "trace: unknown key " (pr-str k)))

        (nil? path)
        ["trace needs a path"]

        (not (string? path))
        ["trace: path must be a string"]

        (str/starts-with? path "~")
        ["trace: path can't start with ~, which isn't expanded; use an absolute path"]

        (or (#{"." "/"} path) (not (or (paths/absolute-dir? path) (paths/relative-dir? path))))
        ["trace: path must name a file, absolute or relative to the config's directory, with no empty, . or .. segments"]))))

(defn- structure-errors [config]
  (let [{:strs [servers scopes runners]} config]
    (concat
     (for [k (keys config)
           :when (not (#{"servers" "scopes" "runners" "cache" "trace"} k))]
       (str "unknown section " (pr-str k)))
     (when (contains? config "cache")
       (cache-errors (get config "cache")))
     (when (contains? config "trace")
       (trace-errors (get config "trace")))
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

(defn implicit?
  "Whether the `slot` is there only because no scope names its server type."
  [config [server-type scope]]
  (boolean (:implicit (meta (get-in config ["scopes" scope server-type])))))

(defn- about-slot [config [server-type scope :as slot] message]
  (if (implicit? config slot)
    (str "server type " (pr-str server-type) ", which no scope names: " message)
    (str "scope " (pr-str scope) ", server type " (pr-str server-type) ": " message)))

(defn- scope-runner-errors [config]
  (for [slot (spec/slots config)
        e (spec/errors config slot)]
    (about-slot config slot e)))

(defn- checked [config source find-errors]
  (let [errors (find-errors config)]
    (when (seq errors) (invalid source errors))
    config))

(defn expand
  "The structurally valid `config` with each scope's entry for a server type
  made into that slot's whole boot config: the scope's values over the server
  type's `boot` table, over the defaults of the runner they name, other than
  any `runner` in those defaults, since a runner can't choose itself. A key
  whose value ends up `false` is left out, which is how a layer unsets a value
  from one below it. Under :sources, each entry's metadata says where any value
  the scope didn't set came from, as [:boot server-type] or [:defaults runner].
  The `boot` tables and runners' `defaults` are then left out, since the scopes
  hold every value of theirs that applies.

  A server type that no scope names runs across the repo, as though the `.`
  scope named it with an empty entry. Its entry's metadata has :implicit true."
  [{:strs! [servers scopes runners] :as config}]
  (let [expand-entry (fn [server-type own]
                       (let [boot (get-in servers [server-type "boot"])
                             runner (get (merge boot own) "runner")
                             defaults (dissoc (get-in runners [runner "defaults"]) "runner")
                             layered (merge defaults boot own)
                             unset (for [[k v] layered :when (false? v)] k)
                             sources (merge (zipmap (keys defaults) (repeat [:defaults runner]))
                                            (zipmap (keys boot) (repeat [:boot server-type])))]
                         (with-meta (apply dissoc layered unset)
                           {:sources (apply dissoc sources (concat (keys own) unset))})))
        named (set (mapcat keys (vals scopes)))
        add-implicit (fn [scopes server-type]
                       (assoc-in scopes ["." server-type]
                                 (vary-meta (expand-entry server-type {}) assoc :implicit true)))]
    (assoc config
           "servers" (update-vals servers #(dissoc % "boot"))
           "runners" (update-vals runners #(dissoc % "defaults"))
           "scopes" (reduce add-implicit
                            (update-vals scopes #(into {} (for [[server-type own] %]
                                                            [server-type (expand-entry server-type own)])))
                            (remove named (keys servers))))))

(defn parse
  "Parses and checks config from a TOML string, with the scopes expanded by
  `expand`. Throws ex-info listing every problem found, under :errors.
  `source` names the file in that message."
  ([s] (parse s file-name))
  ([s source]
   (-> (merge {"servers" {} "scopes" {} "runners" {}} (parse-toml source s))
       (checked source structure-errors)
       expand
       (checked source scope-runner-errors))))

(defn read-file
  "Reads the config in the file `f`, or returns nil if there's no such file."
  [f]
  (let [f (io/file f)]
    (when (.exists f)
      (parse (slurp f) (str f)))))

(defn read-config
  "Reads the config at the root of the repo at `root`, or returns nil if it has
  none."
  [root]
  (read-file (io/file root file-name)))

(defn warnings
  "Values in a valid config's scopes that the scope's runner never uses. These
  aren't errors, since a server type's boot table can set a value for one
  runner that a scope then swaps for another."
  [config]
  (for [slot (spec/slots config)
        w (spec/warnings config slot)]
    (about-slot config slot w)))

(defn warnings-message
  "The `warnings` for the config file at `source`, as one message."
  [source warnings]
  (str/join "\n" (cons (str "unused values in " source ":")
                       (map #(str "  " %) warnings))))

(defn trace-path
  "The file that the config's `trace` table names, or nil if it has none.
  `dir` is the directory holding the config, which a relative `path` is
  resolved against."
  [config dir]
  (when-let [path (get-in config ["trace" "path"])]
    (if (str/starts-with? path "/") path (paths/client-path dir path))))

(defn server-types
  "The server types that handle the file at the repo-relative `path`, which the
  client opened as `language-id` (nil if unknown)."
  [config path language-id]
  (set (for [[name server] (get config "servers")
             :when (or (some #{language-id} (get server "languages"))
                       (some #(glob/matches? % path) (get server "globs")))]
         name)))

(defn languages
  "The languages, sorted, of every server type, or only of those in
  `server-types` if it's non-empty."
  ([config] (languages config nil))
  ([config server-types]
   (let [started (map first (spec/slots config server-types))]
     (vec (sort (distinct (mapcat #(get-in config ["servers" % "languages"]) started)))))))

(defn where-each-runs-message
  "The scopes that each server type runs in, as one message, or nil if the
  config has no server types."
  [config]
  (when-let [by-server-type (not-empty (group-by first (spec/slots config)))]
    (str/join "\n" (cons "Each server type runs in these scopes:"
                         (for [[server-type slots] (sort-by key by-server-type)]
                           (str "  " server-type ": "
                                (str/join ", " (for [[_ scope :as slot] (sort-by second slots)
                                                     :let [also (get-in config ["scopes" scope server-type "also"])]]
                                                 (cond
                                                   (implicit? config slot) (str scope " (the whole repo, since no scope names it)")
                                                   (seq also) (str scope " (also " (str/join ", " also) ")")
                                                   :else scope)))))))))

(defn unknown-server-types
  "Those of the server types `names` that the config doesn't have."
  [config names]
  (remove (set (keys (get config "servers"))) names))

(defn unknown-server-types-message
  "The message for server types `names` that the config file at `source`
  doesn't have."
  [source names]
  (str "There's no server type " (str/join " or " (map pr-str names)) " in " source "."))

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

(defn scopes-for
  "The scopes, sorted, that handle the repo-relative `path` for `server-type`:
  the one that `scope-for` gives, and each whose entry for `server-type` lists
  a directory containing `path` under `also`."
  [config server-type path]
  (let [longest (scope-for config server-type path)]
    (sort (for [[dir entries] (get config "scopes")
                :when (or (= longest dir)
                          (some #(paths/within? % path) (get-in entries [server-type "also"])))]
            dir))))
