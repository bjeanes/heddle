(ns heddle.log
  "Logging to stderr, since stdout is the LSP stream. Clients usually show a
  server's stderr in a log of their own. Tests send it elsewhere with
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
  (let [line (apply print-str "heddle:" parts)]
    (locking lock
      (@writer line))))
