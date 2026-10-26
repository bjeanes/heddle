(ns heddle.spec
  "Backend specs, which hold everything needed to start a backend, resolved
  once the config has loaded. A spec is plain data, with these keys:

  - :slot, the [server-type scope] the backend is for, which outlives restarts;
  - :languages and :globs, the server type's, which say what files it handles;
  - :repo, the repo root as a client path;
  - :dir, the scope's directory on the host, where the runner's commands run;
  - :root, the scope's directory as the server sees it, or nil if the boot
    config's `paths` don't cover it;
  - :paths, the boot config's `paths`;
  - :cache, the directory holding the backend's fetched files;
  - :templates, the runner's command templates;
  - :cfg, the values for those templates' `{cfg:…}` placeholders.

  Since a backend's own values, such as its ID, are only known once it runs,
  `command` takes them separately."
  (:require [clojure.string :as str]
            [heddle.paths :as paths]
            [heddle.runner :as runner]))

(def ^:private scope-end
  "The segment of a cache path between the scope's directories and a fetched
  file's server path. `segment` never gives it for a scope's directory, which
  is never empty."
  "=")

(def ^:private escapes
  {\/ "=2F"
   \= "=3D"})

(def ^:private no-runner
  "What starts a boot config that names no `runner`: its `command`, run
  directly."
  {"start" ["{cfg:command}"]})

(defn- boot
  "The `slot`'s whole boot config, which `heddle.config/expand` has layered
  from the scope, the server type's `boot` table and the runner's defaults."
  [config [server-type scope]]
  (get-in config ["scopes" scope server-type]))

(defn- runner-of
  "The runner that the boot config names, or `no-runner` if it names none."
  [config {:strs [runner]}]
  (if (some? runner) (get-in config ["runners" runner]) no-runner))

(defn- segment
  "`s` as one path segment, with each `/` and `=` as `=` and its hex code.
  Since an empty name or one made only of dots can't name a directory, it gets
  a leading `=`."
  [s]
  (if (re-matches #"\.*" s)
    (str "=" s)
    (str/escape s escapes)))

(defn cache-dir
  "The directory under the repo's cache `root` holding fetched files for the
  `slot`, as a client path. Every backend for the slot uses the same one, in
  any process."
  [root [server-type scope]]
  (str/join "/" (concat [(str/replace root #"/+$" "") (segment server-type)]
                        (when-not (= "." scope) (map segment (str/split scope #"/")))
                        [scope-end])))

(defn spec
  "The spec for the `slot` in a valid config. `repo` is the repo root as a
  client path, and `cache-root` is where fetched files are cached."
  [config [server-type scope :as slot] repo cache-root]
  (let [{:strs [paths] :as boot} (boot config slot)
        {:strs [languages globs]} (get-in config ["servers" server-type])
        runner (runner-of config boot)
        dir (paths/client-path repo scope)]
    {:slot slot
     :languages (vec languages)
     :globs (vec globs)
     :repo repo
     :dir dir
     :root (paths/to-server paths repo dir)
     :paths paths
     :cache (cache-dir cache-root slot)
     :templates (select-keys runner runner/templates)
     :cfg (runner/cfg boot)}))

(defn slots
  "Every [server-type scope] that the config's scopes start, or only those of
  `server-types` if it's non-empty."
  ([config] (slots config nil))
  ([config server-types]
   (let [served? (if (seq server-types) (set server-types) (constantly true))]
     (for [[scope entries] (get config "scopes")
           server-type (keys entries)
           :when (served? server-type)]
       [server-type scope]))))

(defn specs
  "The spec for each of the `slots` of `server-types`, by slot."
  [config repo cache-root server-types]
  (into {} (for [slot (slots config server-types)]
             [slot (spec config slot repo cache-root)])))

(defn command
  "The arguments for the spec's `template` (\"start\", \"read\" or \"prepare\"),
  or nil if the runner has no such template. `runtime` has the :backend-id
  and, for `read`, the server path of the :file to print."
  [{:keys! [templates cfg] :as spec} template runtime]
  (runner/expand templates template (runner/context cfg spec runtime)))

(defn- paths-errors [{:strs [paths] :as boot}]
  (cond
    (not (contains? boot "paths")) nil
    (or (nil? paths) (= {} paths)) ["paths can't be empty; use paths = false to unset it"] ; an inline {} reads as nil
    (not (map? paths)) ["paths must be a table"]
    :else (for [[dir server] paths
                :let [problem (cond (not (paths/relative-dir? dir)) "must be relative to the repo root"
                                    (not (paths/absolute-dir? server)) "must map to an absolute directory")]
                :when problem]
            (str "paths entry " (pr-str dir) " " problem))))

(def ^:private stand-ins
  "Values for checking a spec before any backend is running."
  {:repo "/repo" :cache-root "/cache" :backend-id "id" :file "/file"})

(defn errors
  "Problems with starting the `slot` in a config whose structure is valid. It
  expands the templates with stand-ins for the values only known once a
  backend runs."
  [config slot]
  (let [{:strs [runner] :as boot} (boot config slot)
        path-errors (paths-errors boot)]
    (cond
      (and (some? runner) (not (string? runner))) ["runner must be a string"]
      (and (some? runner) (not (contains? (get config "runners") runner))) [(str "unknown runner " (pr-str runner))]
      (seq path-errors) path-errors
      :else
      (let [{:keys! [root templates cfg] :as s} (spec config slot (:repo stand-ins) (:cache-root stand-ins))
            ctx (runner/context cfg s (select-keys stand-ins [:backend-id :file]))]
        (if-not root
          [runner/uncovered-scope]
          (for [t runner/templates
                :when (contains? templates t)
                e (second (runner/expand* templates t ctx))]
            (str (if runner (str "runner " (pr-str runner) ", " t) "when run directly") ": " e)))))))

(defn warnings
  "Values that the `slot`'s boot config sets but its runner never uses. Values
  from the runner's defaults don't count, since the config doesn't set them."
  [config slot]
  (let [{:strs [runner] :as boot} (boot config slot)
        defaulted (for [[k [origin]] (:sources (meta boot))
                        :when (= :defaults origin)]
                    k)]
    (for [k (runner/unused-values (runner-of config boot) (apply dissoc boot defaulted))]
      (str (pr-str k) " isn't used " (if runner (str "by runner " (pr-str runner)) "when run directly")))))
