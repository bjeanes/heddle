(ns heddle.capabilities
  "The features backends offer for files, and how the proxy announces them to
  the client. See \"Startup and capabilities\" in DESIGN.md.

  A client says, feature by feature, whether it accepts registrations after
  startup. Those features are registered once a backend that offers them has
  started, with one registration for each feature. The rest are announced in
  the proxy's own `initialize` reply, as are the features of any backends
  started before it."
  (:require [clojure.string :as str])
  (:import (java.net URI)))

(def ^:private document-features
  "Each feature's request method, the server capability that offers it, its
  key in the client's `textDocument` capabilities, and what to announce for it
  when the client can't register it later. That last is nil where only a
  backend could supply the feature's options."
  (mapv
   (fn [[method provider client static]]
     {:method method :provider provider :client ["textDocument" client] :static static})
   [["textDocument/hover" "hoverProvider" "hover" true]
    ["textDocument/completion" "completionProvider" "completion" {}]
    ["textDocument/signatureHelp" "signatureHelpProvider" "signatureHelp" {}]
    ["textDocument/declaration" "declarationProvider" "declaration" true]
    ["textDocument/definition" "definitionProvider" "definition" true]
    ["textDocument/typeDefinition" "typeDefinitionProvider" "typeDefinition" true]
    ["textDocument/implementation" "implementationProvider" "implementation" true]
    ["textDocument/references" "referencesProvider" "references" true]
    ["textDocument/documentHighlight" "documentHighlightProvider" "documentHighlight" true]
    ["textDocument/documentSymbol" "documentSymbolProvider" "documentSymbol" true]
    ["textDocument/codeAction" "codeActionProvider" "codeAction" true]
    ["textDocument/codeLens" "codeLensProvider" "codeLens" {}]
    ["textDocument/documentLink" "documentLinkProvider" "documentLink" {}]
    ["textDocument/documentColor" "colorProvider" "colorProvider" true]
    ["textDocument/formatting" "documentFormattingProvider" "formatting" true]
    ["textDocument/rangeFormatting" "documentRangeFormattingProvider" "rangeFormatting" true]
    ["textDocument/onTypeFormatting" "documentOnTypeFormattingProvider" "onTypeFormatting" nil]
    ["textDocument/rename" "renameProvider" "rename" true]
    ["textDocument/foldingRange" "foldingRangeProvider" "foldingRange" true]
    ["textDocument/selectionRange" "selectionRangeProvider" "selectionRange" true]
    ["textDocument/prepareCallHierarchy" "callHierarchyProvider" "callHierarchy" true]
    ["textDocument/semanticTokens" "semanticTokensProvider" "semanticTokens" nil]
    ["textDocument/linkedEditingRange" "linkedEditingRangeProvider" "linkedEditingRange" true]
    ["textDocument/moniker" "monikerProvider" "moniker" true]
    ["textDocument/prepareTypeHierarchy" "typeHierarchyProvider" "typeHierarchy" true]
    ["textDocument/inlineValue" "inlineValueProvider" "inlineValue" true]
    ["textDocument/inlayHint" "inlayHintProvider" "inlayHint" true]
    ["textDocument/diagnostic" "diagnosticProvider" "diagnostic"
     {"interFileDependencies" false "workspaceDiagnostics" false}]]))

(def ^:private execute-command
  "Running a command, which isn't about any one file. Some clients only run
  commands that a server has listed in its options."
  {:method "workspace/executeCommand"
   :provider "executeCommandProvider"
   :client ["workspace" "executeCommand"]
   :static {"commands" []}})

(def ^:private features
  (conj document-features execute-command))

(def ^:private workspace-symbol
  "Workspace symbol search, which the proxy announces at startup whatever the
  client supports. It isn't about any one file, and the proxy asks every
  running backend itself, answering with nothing before any has started."
  {:method "workspace/symbol"
   :provider "workspaceSymbolProvider"
   :static {"resolveProvider" true}})

(defn- document-feature? [feature]
  (= "textDocument" (first (:client feature))))

(def ^:private resolve-methods
  "Requests that resolve an item, and the requests whose answers hold the
  items. A backend resolves them if its options for the feature say so."
  {"completionItem/resolve" "textDocument/completion"
   "codeAction/resolve" "textDocument/codeAction"
   "codeLens/resolve" "textDocument/codeLens"
   "documentLink/resolve" "textDocument/documentLink"
   "inlayHint/resolve" "textDocument/inlayHint"
   "workspaceSymbol/resolve" "workspace/symbol"})

(def ^:private follow-up-methods
  "Requests about an item, held in their `item` param, from the answer to
  another request, and those requests."
  {"callHierarchy/incomingCalls" "textDocument/prepareCallHierarchy"
   "callHierarchy/outgoingCalls" "textDocument/prepareCallHierarchy"
   "typeHierarchy/supertypes" "textDocument/prepareTypeHierarchy"
   "typeHierarchy/subtypes" "textDocument/prepareTypeHierarchy"})

(def ^:private related-methods
  "Requests that belong to a feature registered under another method."
  (merge follow-up-methods
         {"textDocument/semanticTokens/full" "textDocument/semanticTokens"
          "textDocument/semanticTokens/full/delta" "textDocument/semanticTokens"
          "textDocument/semanticTokens/range" "textDocument/semanticTokens"
          "textDocument/colorPresentation" "textDocument/documentColor"
          "textDocument/prepareRename" "textDocument/rename"}))

(def ^:private by-method
  (into {} (map (juxt :method identity)) (conj features workspace-symbol)))

(defn feature
  "The feature that the request `method` uses, or nil if it isn't one the proxy
  knows about."
  [method]
  (get by-method (get related-methods method method)))

(defn offers?
  "Whether a backend with the server capabilities `server-caps` offers
  `feature`."
  [server-caps feature]
  (let [v (get server-caps (:provider feature))]
    (and (some? v) (not= false v))))

(defn resolve-method?
  "Whether the request `method` resolves an item from an earlier answer."
  [method]
  (contains? resolve-methods method))

(defn follow-up-method?
  "Whether the request `method` is about an item, held in its `item` param,
  from an earlier answer."
  [method]
  (contains? follow-up-methods method))

(defn resolves?
  "Whether a backend with the server capabilities `server-caps` answers the
  request `method`, which resolves an item."
  [server-caps method]
  (let [provider (:provider (get by-method (get resolve-methods method)))]
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
  a backend of lower priority. Lists such as trigger characters are combined,
  and a flag such as `resolveProvider` is set if either sets it. Otherwise the
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

(defn- uri-path [uri]
  (when (string? uri)
    (try (.getPath (URI. uri)) (catch Exception _ nil))))

(defn- under? [dir path]
  (and dir path (or (= dir path) (str/starts-with? path (str dir "/")))))

(defn- narrow-pattern
  "A backend's `pattern` limited to the proxy's `own` relative pattern, or nil
  if a document filter can't express that. Two globs can't be combined, so
  only a pattern for a whole directory can narrow another."
  [pattern own]
  (let [base (uri-path (get own "baseUri"))]
    (cond
      (not= "**/*" (get own "pattern")) nil
      (map? pattern) (when (under? base (uri-path (get pattern "baseUri"))) pattern)
      (not (string? pattern)) nil
      (str/starts-with? pattern "/") (when (under? base pattern) pattern)
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

(def ^:private combined-methods
  "The requests whose answers can be combined. They go to every backend for the
  file that offers the feature, or every running backend for workspace
  symbols. The rest want one answer:

  - hover, signature help and document symbols describe the code in one
    server's terms, and two servers' descriptions would repeat or contradict
    each other;
  - definition, declaration, type definition, implementation and document
    highlights point at the same code, so a second server would mostly add
    duplicates;
  - formatting and rename are edits, and two servers' edits to the same text
    would conflict;
  - folding and selection ranges, semantic tokens, colours, linked editing
    ranges, monikers, inline values and the call and type hierarchies each
    cover the whole file or symbol, which a client expects from one server.

  Smarter merging of some of these is pinned for later in DESIGN.md."
  #{"textDocument/completion"
    "textDocument/codeAction"
    "textDocument/codeLens"
    "textDocument/references"
    "textDocument/documentLink"
    "textDocument/inlayHint"
    "textDocument/diagnostic"
    "workspace/symbol"})

(defn combined?
  "Whether the answers to the request `method` from several backends are
  combined, as opposed to one backend answering."
  [method]
  (contains? combined-methods method))

(defn empty-result
  "The result for a request when no backend for its file offers its feature."
  [method]
  (when (= "textDocument/diagnostic" method)
    {"kind" "full" "items" []}))
