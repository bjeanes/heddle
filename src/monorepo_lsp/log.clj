(ns monorepo-lsp.log
  "Logging to stderr. Clients usually show a server's stderr in a log of their
  own, and stdout is the LSP stream.")

(def ^:private lock (Object.))

(defn info [& parts]
  (locking lock
    (binding [*out* *err*]
      (apply println "monorepo-lsp:" parts)
      (flush))))
