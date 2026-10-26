(ns monorepo-lsp.log
  "Logging to stderr. Clients usually show a server's stderr in a log of their
  own, and stdout is the LSP stream. Tests send it elsewhere with
  `set-writer!`.")

(def ^:private lock (Object.))

(defn- to-stderr [line]
  (binding [*out* *err*]
    (println line)
    (flush)))

(def ^:private writer (atom to-stderr))

(defn set-writer!
  "Sends each line logged from now on to `f` instead of stderr, from any
  thread. Returns the previous writer."
  [f]
  (first (reset-vals! writer f)))

(defn info [& parts]
  (let [line (apply print-str "monorepo-lsp:" parts)]
    (locking lock
      (@writer line))))
