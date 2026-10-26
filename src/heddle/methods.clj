(ns heddle.methods
  "What the proxy needs to know about each LSP request it handles, from
  announcing the feature to combining backends' answers. Each namespace keeps
  its own behaviour and reads the parts of a request's description it needs.

  The requests that aren't combined want one answer:

  - Hover, signature help and document symbols describe the code in one
    server's terms. Two servers' descriptions would repeat or contradict each
    other.
  - Definition, declaration, type definition, implementation and document
    highlights point at the same code. A second server would mostly add
    duplicates.
  - Formatting and rename are edits. Two servers' edits to the same text would
    conflict.
  - Folding and selection ranges, semantic tokens, colours, linked editing
    ranges, monikers, inline values and the call and type hierarchies each
    cover the whole file or symbol, which a client expects from one server.

  Smarter merging of some of these is pinned for later in DESIGN.md.")

(def description-keys
  #{:provider  ; the server capability that offers the feature
    :client    ; where the client's capabilities say whether it can register the feature later
    :static    ; what to announce when it can't, if a stand-in for a backend's options will do
    :feature   ; the request whose feature this one belongs to
    :follows   ; the request whose answer holds this one's `item` param
    :resolves  ; the request whose answer holds the items this one resolves
    :normalise ; how to put one backend's answer into the shape the proxy handles
    :combine   ; how to combine answers, for a request sent to every backend offering the feature
    :dedupe-by ; the paths to the values that identify a combined item; the proxy keeps the first item with each
    :items     ; the path to the answer's items, which are marked with their backend; :each is every element
    :commands? ; whether the answer can hold commands
    :empty})   ; the answer when no backend offers the feature, if not null

(def table
  {"textDocument/hover"
   {:provider "hoverProvider" :client ["textDocument" "hover"] :static true}

   "textDocument/completion"
   {:provider "completionProvider" :client ["textDocument" "completion"] :static {}
    :normalise :completion-list
    :combine :completions
    :items ["items" :each]
    :commands? true}

   "completionItem/resolve"
   {:resolves "textDocument/completion" :items [] :commands? true}

   "textDocument/signatureHelp"
   {:provider "signatureHelpProvider" :client ["textDocument" "signatureHelp"] :static {}}

   "textDocument/declaration"
   {:provider "declarationProvider" :client ["textDocument" "declaration"] :static true}

   "textDocument/definition"
   {:provider "definitionProvider" :client ["textDocument" "definition"] :static true}

   "textDocument/typeDefinition"
   {:provider "typeDefinitionProvider" :client ["textDocument" "typeDefinition"] :static true}

   "textDocument/implementation"
   {:provider "implementationProvider" :client ["textDocument" "implementation"] :static true}

   "textDocument/references"
   {:provider "referencesProvider" :client ["textDocument" "references"] :static true
    :combine :concat
    :dedupe-by [["uri"] ["range"]]}

   "textDocument/documentHighlight"
   {:provider "documentHighlightProvider" :client ["textDocument" "documentHighlight"] :static true}

   "textDocument/documentSymbol"
   {:provider "documentSymbolProvider" :client ["textDocument" "documentSymbol"] :static true}

   "textDocument/codeAction"
   {:provider "codeActionProvider" :client ["textDocument" "codeAction"] :static true
    :combine :code-actions
    :items [:each]
    :commands? true}

   "codeAction/resolve"
   {:resolves "textDocument/codeAction" :items [] :commands? true}

   "textDocument/codeLens"
   {:provider "codeLensProvider" :client ["textDocument" "codeLens"] :static {}
    :combine :concat
    :items [:each]
    :commands? true}

   "codeLens/resolve"
   {:resolves "textDocument/codeLens" :items [] :commands? true}

   "textDocument/documentLink"
   {:provider "documentLinkProvider" :client ["textDocument" "documentLink"] :static {}
    :combine :concat
    :items [:each]}

   "documentLink/resolve"
   {:resolves "textDocument/documentLink" :items []}

   "textDocument/documentColor"
   {:provider "colorProvider" :client ["textDocument" "colorProvider"] :static true}

   "textDocument/colorPresentation"
   {:feature "textDocument/documentColor"}

   "textDocument/formatting"
   {:provider "documentFormattingProvider" :client ["textDocument" "formatting"] :static true}

   "textDocument/rangeFormatting"
   {:provider "documentRangeFormattingProvider" :client ["textDocument" "rangeFormatting"] :static true}

   "textDocument/onTypeFormatting"
   {:provider "documentOnTypeFormattingProvider" :client ["textDocument" "onTypeFormatting"]}

   "textDocument/rename"
   {:provider "renameProvider" :client ["textDocument" "rename"] :static true}

   "textDocument/prepareRename"
   {:feature "textDocument/rename"}

   "textDocument/foldingRange"
   {:provider "foldingRangeProvider" :client ["textDocument" "foldingRange"] :static true}

   "textDocument/selectionRange"
   {:provider "selectionRangeProvider" :client ["textDocument" "selectionRange"] :static true}

   "textDocument/prepareCallHierarchy"
   {:provider "callHierarchyProvider" :client ["textDocument" "callHierarchy"] :static true
    :items [:each]}

   "callHierarchy/incomingCalls"
   {:follows "textDocument/prepareCallHierarchy" :items [:each "from"]}

   "callHierarchy/outgoingCalls"
   {:follows "textDocument/prepareCallHierarchy" :items [:each "to"]}

   "textDocument/semanticTokens"
   {:provider "semanticTokensProvider" :client ["textDocument" "semanticTokens"]}

   "textDocument/semanticTokens/full"
   {:feature "textDocument/semanticTokens"}

   "textDocument/semanticTokens/full/delta"
   {:feature "textDocument/semanticTokens"}

   "textDocument/semanticTokens/range"
   {:feature "textDocument/semanticTokens"}

   "textDocument/linkedEditingRange"
   {:provider "linkedEditingRangeProvider" :client ["textDocument" "linkedEditingRange"] :static true}

   "textDocument/moniker"
   {:provider "monikerProvider" :client ["textDocument" "moniker"] :static true}

   "textDocument/prepareTypeHierarchy"
   {:provider "typeHierarchyProvider" :client ["textDocument" "typeHierarchy"] :static true
    :items [:each]}

   "typeHierarchy/supertypes"
   {:follows "textDocument/prepareTypeHierarchy" :items [:each]}

   "typeHierarchy/subtypes"
   {:follows "textDocument/prepareTypeHierarchy" :items [:each]}

   "textDocument/inlineValue"
   {:provider "inlineValueProvider" :client ["textDocument" "inlineValue"] :static true}

   "textDocument/inlayHint"
   {:provider "inlayHintProvider" :client ["textDocument" "inlayHint"] :static true
    :combine :concat
    :items [:each]
    :commands? true}

   "inlayHint/resolve"
   {:resolves "textDocument/inlayHint" :items [] :commands? true}

   "textDocument/diagnostic"
   {:provider "diagnosticProvider" :client ["textDocument" "diagnostic"]
    :static {"interFileDependencies" false "workspaceDiagnostics" false}
    :combine :diagnostic-reports
    :empty {"kind" "full" "items" []}}

   "workspace/executeCommand"
   {:provider "executeCommandProvider" :client ["workspace" "executeCommand"] :static {"commands" []}}

   "workspace/symbol"
   {:provider "workspaceSymbolProvider" :static {"resolveProvider" true}
    :combine :concat
    :dedupe-by [["name"] ["kind"] ["location" "uri"] ["location" "range"]]
    :items [:each]}

   "workspaceSymbol/resolve"
   {:resolves "workspace/symbol" :items []}})
