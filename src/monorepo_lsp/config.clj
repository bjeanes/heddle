(ns monorepo-lsp.config
  "Reads `.monorepo-lsp.toml` and answers which server types handle a file and
  how each is started. Keys stay strings, as in the TOML. See DESIGN.md for the
  meaning of server types, scopes and boot configs."
  (:require [clj-toml.core :as toml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [monorepo-lsp.glob :as glob]
            [monorepo-lsp.paths :as paths]))

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
       (for [k ["languages" "globs"]
             :when (and (contains? server k) (not (string-list? (get server k))))]
         (str where ": " k " must be a list of strings"))
       (when-not (or (contains? server "languages") (contains? server "globs"))
         [(str where " matches no files; give it languages or globs")])
       (when-not (map? (get server "boot" {}))
         [(str where ": boot must be a table")])))))

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

(defn- structure-errors [config]
  (let [{:strs [servers scopes]} config]
    (concat
     (for [k (keys config)
           :when (not (#{"servers" "scopes" "runners"} k))]
       (str "unknown section " (pr-str k)))
     (for [k ["servers" "scopes" "runners"]
           :when (not (map? (get config k)))]
       (str k " must be a table"))
     (when (map? servers) (mapcat (fn [[k v]] (server-errors k v)) servers))
     (when (and (map? servers) (map? scopes))
       (mapcat (fn [[k v]] (scope-errors servers k v)) scopes)))))

(defn- checked [config source find-errors]
  (let [errors (find-errors config)]
    (when (seq errors) (invalid source errors))
    config))

(defn parse
  "Parses and checks config from a TOML string. Throws ex-info listing every
  problem found, under :errors. `source` names the file in that message."
  ([s] (parse s file-name))
  ([s source]
   (-> (merge {"servers" {} "scopes" {} "runners" {}} (parse-toml source s))
       (checked source structure-errors))))

(defn read-config
  "Reads the config at the root of the repo at `root`, or returns nil if it has
  none."
  [root]
  (let [f (io/file root file-name)]
    (when (.exists f)
      (parse (slurp f) (str f)))))

(defn server-types
  "The server types that handle the file at the repo-relative `path`, which the
  client opened as `language-id` (nil if unknown)."
  [config path language-id]
  (set (for [[name server] (get config "servers")
             :when (or (some #{language-id} (get server "languages"))
                       (some #(glob/matches? % path) (get server "globs")))]
         name)))

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
