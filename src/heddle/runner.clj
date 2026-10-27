(ns heddle.runner
  "Runners and the expansion of their command templates. See \"Runners\" in
  DESIGN.md."
  (:require [clj-toml.core :as toml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [heddle.paths :as paths]))

(defmacro ^:private inline-resource
  "`jolt build` doesn't bundle resources into the binary, so this reads the
  file when the namespace compiles."
  [path]
  (slurp (io/resource path)))

(def builtin-toml (inline-resource "heddle/runners.toml"))

(def builtins (get (toml/parse-string builtin-toml) "runners"))

(def templates ["start" "read" "prepare"])

(def ^:private placeholder #"\{([A-Za-z0-9_-]+):([A-Za-z0-9_.-]+)\}")

(def ^:private provided
  {"client" #{"scope" "repo"}
   "server" #{"scope"}
   "read" #{"file"}
   "proxy" #{"backend_id"}})

(def uncovered-scope "paths don't cover the scope's directory")

(defn string-list? [v]
  (and (vector? v) (every? string? v)))

(defn- placeholder-error
  "A problem with `{owner:name}` that doesn't depend on any scope, or nil."
  [template owner name]
  (cond
    (= "cfg" owner) nil
    (not (get-in provided [owner name])) (str "unknown placeholder {" owner ":" name "}")
    (and (= "read" owner) (not= "read" template)) (str "{read:" name "} is only available in read")))

(defn- placeholders
  "Each `{owner:name}` in the runner's templates, as [template owner name]."
  [runner]
  (for [t templates
        :let [template (get runner t)]
        :when (string-list? template)
        element template
        [_ owner name] (re-seq placeholder element)]
    [t owner name]))

(defn paths-errors
  "Problems with the `paths` in a boot config or a runner's defaults that don't
  depend on any scope. A `false` there unsets it."
  [{:strs [paths] :as boot}]
  (cond
    (or (not (contains? boot "paths")) (false? paths)) nil
    (= {} paths) ["paths can't be empty; use paths = false to unset it"]
    (not (map? paths)) ["paths must be a table"]
    :else (for [[dir server] paths
                :let [problem (cond (not (paths/relative-dir? dir)) "must be relative to the repo root"
                                    (not (paths/absolute-dir? server)) "must map to an absolute directory")]
                :when problem]
            (str "paths entry " (pr-str dir) " " problem))))

(defn runner-errors
  "Problems with a runner's definition that don't depend on any scope."
  [runner]
  (if-not (map? runner)
    ["must be a table"]
    (concat
     (for [k (keys runner)
           :when (not (#{"start" "read" "prepare" "defaults"} k))]
       (str "unknown key " (pr-str k)))
     (when-not (contains? runner "start")
       ["has no start template"])
     (let [defaults (get runner "defaults" {})]
       (if (map? defaults)
         (for [e (paths-errors defaults)] (str "defaults: " e))
         ["defaults must be a table"]))
     (for [t templates
           :let [template (get runner t)]
           :when (and template (not (string-list? template)))]
       (str t " must be a list of strings"))
     (for [[t owner name] (placeholders runner)
           :let [e (placeholder-error t owner name)]
           :when e]
       (str t ": " e)))))

(defn cfg
  "The boot config's values for `{cfg:…}` placeholders."
  [boot]
  (dissoc boot "runner" "paths"))

(defn unused-values
  "The names, sorted, of the boot config's values that none of the runner's
  templates use."
  [runner boot]
  (let [used (set (for [[_ owner name] (placeholders runner)
                        :when (= "cfg" owner)]
                    name))]
    (sort (remove used (keys (cfg boot))))))

(defn- value-error
  "Why `{owner:name}` has no usable value in `ctx`, or nil."
  [ctx owner name]
  (or (placeholder-error (:template ctx) owner name)
      (case owner
        "cfg" (let [v (get (:cfg ctx) name)]
                (cond
                  (:in-value ctx) (str "the value of {cfg:" (:in-value ctx) "} can't use {cfg:" name "}")
                  (not (contains? (:cfg ctx) name)) (str "no value for {cfg:" name "}")
                  (not (or (string? v) (string-list? v))) (str "{cfg:" name "} must be a string or a list of strings")))
        "server" (when-not (:server-scope ctx) uncovered-scope)
        nil)))

(defn- provided-value [ctx owner name]
  (get {"client:scope" (:client-scope ctx)
        "client:repo" (:repo ctx)
        "server:scope" (:server-scope ctx)
        "read:file" (:file ctx)
        "proxy:backend_id" (:backend-id ctx)}
       (str owner ":" name)))

(declare fill)

(defn- value-of
  "A string, a list of strings, or nil after recording an error."
  [ctx owner name errors]
  (if-let [e (value-error ctx owner name)]
    (do (vswap! errors conj e) nil)
    (if (= "cfg" owner)
      (let [v (get (:cfg ctx) name)
            ctx (assoc ctx :in-value name)]
        (if (string? v)
          (fill ctx v errors)
          (mapv #(fill ctx % errors) v)))
      (provided-value ctx owner name))))

(defn- fill
  "Replaces placeholders inside the string `s`, each of which has to be a
  string."
  [ctx s errors]
  (str/replace s placeholder
               (fn [[whole owner name]]
                 (let [v (value-of ctx owner name errors)]
                   (when (vector? v)
                     (vswap! errors conj (str whole " is a list, which has to make up a whole element")))
                   (if (string? v) v whole)))))

(defn- expand-element [ctx element errors]
  (if-let [[_ owner name] (re-matches placeholder element)]
    (let [v (value-of ctx owner name errors)]
      (cond (string? v) [v]
            (nil? v) []
            :else v))
    [(fill ctx element errors)]))

(defn context
  "What a runner's templates can draw on. `cfg` is from `cfg`. `scope` has the
  client paths of the :repo root and the scope's :dir, and under :root the
  scope's directory as the server sees it, or nil if the boot config's `paths`
  don't cover it. `runtime` has the :backend-id and the :file (for `read`)
  where known."
  [cfg {:keys [repo dir root]} runtime]
  (assoc runtime :cfg cfg :repo repo :client-scope dir :server-scope root))

(defn expand*
  "Expands the template named `template` in `ctx` from `context`. Returns
  [args errors]."
  [runner template ctx]
  (let [errors (volatile! [])
        ctx (assoc ctx :template template)
        args (into [] (mapcat #(expand-element ctx % errors)) (get runner template))]
    [args (vec (distinct @errors))]))

(defn expand
  "The arguments for the template named `template`, or nil if the runner has
  none by that name. Throws ex-info listing problems under :errors."
  [runner template ctx]
  (when (contains? runner template)
    (let [[args errors] (expand* runner template ctx)]
      (when (seq errors)
        (throw (ex-info (str "can't expand " template ": " (str/join "; " errors))
                        {:template template :errors errors})))
      args)))
