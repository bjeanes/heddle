(ns monorepo-lsp.capabilities
  "The features backends offer for files, and how the proxy announces them to
  the client. See \"Startup and capabilities\" in DESIGN.md.

  A client says, feature by feature, whether it accepts registrations after
  startup. Those features are registered for each backend once it has started.
  The rest are announced in the proxy's own `initialize` reply.")

(def ^:private features
  "Each feature's request method, the server capability that offers it, its
  key in the client's `textDocument` capabilities, and what to announce for it
  when the client can't register it later. That last is nil where only a
  backend could supply the feature's options."
  (mapv
   #(zipmap [:method :provider :client :static] %)
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

(def ^:private resolve-methods
  "Requests that resolve an item, and the requests whose answers hold the
  items. A backend resolves them if its options for the feature say so."
  {"completionItem/resolve" "textDocument/completion"
   "codeAction/resolve" "textDocument/codeAction"
   "codeLens/resolve" "textDocument/codeLens"
   "documentLink/resolve" "textDocument/documentLink"
   "inlayHint/resolve" "textDocument/inlayHint"})

(def ^:private related-methods
  "Requests that belong to a feature registered under another method."
  {"textDocument/semanticTokens/full" "textDocument/semanticTokens"
   "textDocument/semanticTokens/full/delta" "textDocument/semanticTokens"
   "textDocument/semanticTokens/range" "textDocument/semanticTokens"
   "textDocument/colorPresentation" "textDocument/documentColor"
   "textDocument/prepareRename" "textDocument/rename"})

(def ^:private by-method
  (into {} (map (juxt :method identity)) features))

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

(defn resolves?
  "Whether a backend with the server capabilities `server-caps` answers the
  request `method`, which resolves an item."
  [server-caps method]
  (let [provider (:provider (get by-method (get resolve-methods method)))]
    (true? (get-in server-caps [provider "resolveProvider"]))))

(defn- registrable? [client-caps feature]
  (true? (get-in client-caps ["textDocument" (:client feature) "dynamicRegistration"])))

(def ^:private sync-everything
  {"openClose" true
   "change" 1
   "save" true})

(defn initialize-result
  "The proxy's answer to the client's `initialize`, given the client's
  capabilities."
  [client-caps]
  {"capabilities" (into {"textDocumentSync" sync-everything
                         "positionEncoding" "utf-16"}
                        (for [f features
                              :when (and (some? (:static f)) (not (registrable? client-caps f)))]
                          [(:provider f) (:static f)]))
   "serverInfo" {"name" "monorepo-lsp"}})

(defn registrations
  "The registrations for `client/registerCapability` of the features a backend
  offers that the client accepts registrations for. Each covers the files in
  `document-selector`, and its ID starts with `id-prefix`."
  [client-caps server-caps document-selector id-prefix]
  (vec (for [f features
             :when (and (registrable? client-caps f) (offers? server-caps f))
             :let [options (get server-caps (:provider f))]]
         {"id" (str id-prefix ":" (:method f))
          "method" (:method f)
          "registerOptions" (assoc (if (map? options) options {})
                                   "documentSelector" document-selector)})))

(def ^:private combined-methods
  "The requests whose answers can be combined. They go to every backend for the
  file that offers the feature. The rest want one answer:

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
    "textDocument/inlayHint"})

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
