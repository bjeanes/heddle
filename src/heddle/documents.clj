(ns heddle.documents
  "The documents the client has open, as a value of the shape
  {uri {:language :version :text}}. Each holds the text the client last sent,
  so that the proxy can open the document in a backend that starts later.
  heddle.registry keeps track of which backends have been sent each one.")

(defn opened
  "`documents` with the one that the client opened with the `didOpen` `params`."
  [documents {:strs [textDocument]}]
  (let [{:strs [uri languageId version text]} textDocument]
    (assoc documents uri {:language languageId :version version :text text})))

(defn open-params
  "The params of a `didOpen` for the document `doc` at `uri`."
  [uri {:keys [language version text]}]
  {"textDocument" {"uri" uri "languageId" language "version" version "text" text}})

(defn changed
  "`documents` with the latest version and text of the one that `didChange`
  is about. The proxy asks the client for the full text on every change."
  [documents {:strs [textDocument contentChanges]}]
  (let [uri (get textDocument "uri")]
    (cond-> documents
      (contains? documents uri)
      (update uri assoc
              :version (get textDocument "version")
              :text (get (last contentChanges) "text")))))
