(ns heddle.capabilities
  "The features backends offer for files, and how the proxy announces them to
  the client. See \"Startup and capabilities\" in DESIGN.md.

  A client says, feature by feature, whether it accepts registrations after
  startup. Those features are registered once a backend that offers them has
  started, with one registration for each feature. The rest are announced in
  the proxy's own `initialize` reply, as are the features of any backends
  started before it."
  (:require [clojure.string :as str]
            [heddle.methods :as methods]
            [heddle.paths :as paths]))

(defn- feature-of
  "The feature offered under the request `method`, or nil if no server
  capability offers one under it."
  [method]
  (let [description (get methods/table method)]
    (when (:provider description)
      (assoc description :method method))))

(def ^:private features
  "The features that the client may accept registrations for, in a stable
  order."
  (sort-by :method (for [[method description] methods/table
                         :when (:client description)]
                     (feature-of method))))

(def ^:private workspace-symbol
  "Workspace symbol search, which the proxy announces at startup whatever the
  client supports. It isn't about any one file. The proxy asks every running
  backend itself, answering with nothing before any has started."
  (feature-of "workspace/symbol"))

(defn- document-feature? [feature]
  (= "textDocument" (first (:client feature))))

(defn feature
  "The feature that the request `method` uses, or nil if it isn't one the proxy
  knows about."
  [method]
  (let [{related :feature follows :follows} (get methods/table method)]
    (feature-of (or related follows method))))

(defn offers?
  "Whether a backend with the server capabilities `server-caps` offers
  `feature`."
  [server-caps feature]
  (let [v (get server-caps (:provider feature))]
    (and (some? v) (not= false v))))

(defn resolve-method?
  "Whether the request `method` resolves an item from an earlier answer."
  [method]
  (contains? (get methods/table method) :resolves))

(defn follow-up-method?
  "Whether the request `method` is about an item, held in its `item` param,
  from an earlier answer."
  [method]
  (contains? (get methods/table method) :follows))

(defn resolves?
  "Whether a backend with the server capabilities `server-caps` answers the
  request `method`, which resolves an item. It does if its options for the
  feature say so."
  [server-caps method]
  (let [provider (:provider (feature-of (get-in methods/table [method :resolves])))]
    (true? (get-in server-caps [provider "resolveProvider"]))))

(defn- registrable? [client-caps feature]
  (true? (get-in client-caps (conj (:client feature) "dynamicRegistration"))))

(def ^:private sync-everything
  {"openClose" true
   "change" 1
   "save" true})

(declare merged-options)

(defn- conflicting-legends?
  "Whether `backends` would number semantic tokens differently. The client
  decodes every file's tokens with the one legend it was given."
  [f backends]
  (and (= "textDocument/semanticTokens" (:method f))
       (< 1 (count (distinct (keep #(get-in (first %) [(:provider f) "legend"]) backends))))))

(defn- static-options
  "What to announce for `f` at startup. That is the combined options of the
  `backends` that offer it, if any, or else the feature's stand-in when the
  client can't register it later."
  [client-caps backends f]
  (if-let [options (when-not (conflicting-legends? f backends)
                     (merged-options f backends))]
    (dissoc options "documentSelector")
    (when-not (registrable? client-caps f)
      (:static f))))

(defn initialize-result
  "The proxy's answer to the client's `initialize`, given the client's
  capabilities and the backends that started before it answered. `backends`
  are pairs as `registrations` takes them."
  ([client-caps] (initialize-result client-caps []))
  ([client-caps backends]
   {"capabilities" (into {"textDocumentSync" sync-everything
                          "positionEncoding" "utf-16"
                          (:provider workspace-symbol) (:static workspace-symbol)}
                         (for [f features
                               :let [options (static-options client-caps backends f)]
                               :when (some? options)]
                           [(:provider f) options]))
    "serverInfo" {"name" "heddle"}}))

(defn announced-methods
  "The methods of the features that the server capabilities `caps`, from
  `initialize-result`, announce. These are never registered later."
  [caps]
  (set (for [f features
             :when (contains? caps (:provider f))]
         (:method f))))

(defn- merge-option
  "One backend's value for a registration option combined with another's, from
  a backend of lower priority. Lists such as trigger characters are combined.
  A flag such as `resolveProvider` is set if either sets it. Otherwise the
  first value stands."
  [a b]
  (cond
    (and (sequential? a) (sequential? b)) (vec (distinct (concat a b)))
    (and (boolean? a) (boolean? b)) (or a b)
    :else a))

(defn- merged-options
  "The options for `f` that combine those of every one of `backends` that
  offers it, with a document selector covering all of their files, or nil if
  none offers it."
  [f backends]
  (let [offering (filter #(offers? (first %) f) backends)]
    (when (seq offering)
      (cond-> (apply merge-with merge-option (for [[server-caps] offering
                                                   :let [o (get server-caps (:provider f))]]
                                               (if (map? o) o {})))
        (document-feature? f)
        (assoc "documentSelector" (vec (distinct (mapcat second offering))))))))

(defn registrations
  "The registrations for `client/registerCapability`, one for each feature
  that the client accepts registrations for and any of `backends` offers.
  `backends` are pairs of a backend's server capabilities and its document
  selector, in priority order. A feature's registration combines the options
  of every backend that offers it, and covers all of their files."
  [client-caps backends]
  (vec (for [f features
             :when (registrable? client-caps f)
             :let [options (merged-options f backends)]
             :when options]
         {"id" (:method f)
          "method" (:method f)
          "registerOptions" options})))

(defn- narrow-pattern
  "A backend's `pattern` limited to the proxy's `own` relative pattern, or nil
  if a document filter can't express that. Since two globs can't be combined,
  only a pattern for a whole directory can narrow another."
  [pattern own]
  (let [base (paths/uri->path (get own "baseUri"))]
    (cond
      (not= "**/*" (get own "pattern")) nil
      (map? pattern) (when (paths/under? base (paths/uri->path (get pattern "baseUri"))) pattern)
      (not (string? pattern)) nil
      (str/starts-with? pattern "/") (when (paths/under? base pattern) pattern)
      :else (assoc own "pattern" pattern))))

(defn- narrow-filter
  "A document filter for the files that both a backend's filter `theirs` and
  the proxy's filter `own` match, or nil if there are none or a filter can't
  express them."
  [theirs own]
  (let [language (get theirs "language")
        own-language (get own "language")]
    (when-not (and language own-language (not= language own-language))
      (if-let [pattern (get theirs "pattern")]
        (some->> (narrow-pattern pattern (get own "pattern"))
                 (assoc (merge own theirs) "pattern"))
        (merge own theirs)))))

(defn limit-selector
  "A backend's document `selector`, from a registration of its own, limited to
  `own`, the selector the proxy registers the backend's features with. A
  null selector stands for the client's, which `own` replaces."
  [selector own]
  (if (nil? selector)
    own
    (vec (distinct (for [f selector
                         o own
                         :let [narrowed (narrow-filter f o)]
                         :when narrowed]
                     narrowed)))))

(defn combined?
  "Whether the answers to the request `method` from several backends are
  combined, as opposed to one backend answering. Combined requests go to
  every backend for the file that offers the feature, or every running
  backend for workspace symbols."
  [method]
  (contains? (get methods/table method) :combine))

(defn empty-result
  "The result for a request when no backend for its file offers its feature."
  [method]
  (get-in methods/table [method :empty]))
