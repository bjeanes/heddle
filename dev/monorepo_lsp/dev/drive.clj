(ns monorepo-lsp.dev.drive
  "Drives a language server through one short session, as a client would, and
  prints what comes back. For trying the proxy against real servers by hand."
  (:require [clojure.string :as str]
            [monorepo-lsp.json :as json]
            [monorepo-lsp.lsp :as lsp])
  (:import (java.io BufferedInputStream)))

(def ^:private usage
  "usage: jolt -M:drive --root URI --file PATH --uri URI --language ID
                    [--delay MS] [--hover LINE:CHAR] [--definition LINE:CHAR]
                    [--register FEATURE,...] [--diagnostic true]
                    [--capabilities true]
                    -- COMMAND [ARG...]

  --root          workspace root URI to send in initialize
  --file          file on this machine whose contents are opened
  --uri           URI to open it as (may differ from --file, e.g. a container path)
  --delay         time to wait after opening the file, e.g. for indexing
  --register      textDocument features to accept registrations for after
                  startup, such as hover,definition
  --diagnostic    pull the file's diagnostics and list them
  --capabilities  print the server's capabilities from initialize in full")

(defn- parse-args
  "Options are `--name value` pairs, and the rest is the command. jolt itself
  may consume the `--` before the command, so it is optional."
  [args]
  (loop [opts {} [k v & more :as args] args]
    (if (and k (str/starts-with? k "--") (not= "--" k))
      (recur (assoc opts (keyword (subs k 2)) v) more)
      (assoc opts :command (if (= "--" k) (rest args) args)))))

(defn- position [s]
  (when s
    (let [[line ch] (map #(Long/parseLong %) (str/split s #":"))]
      {"line" line "character" ch})))

(defn- elapsed-ms [t0] (long (/ (- (System/nanoTime) t0) 1e6)))

(defn- abbreviate [v]
  (let [s (json/write-str v)]
    (if (> (count s) 300) (str (subs s 0 300) "…") s)))

(defn- request
  "Sends a request and reads until its response arrives, answering any
  requests the server makes in the meantime."
  [in out id method params]
  (let [t0 (System/nanoTime)]
    (lsp/write-message out {"jsonrpc" "2.0" "id" id "method" method "params" params})
    (loop []
      (let [msg (lsp/read-message in)]
        (cond
          (nil? msg) (throw (ex-info (str "server closed before answering " method) {}))

          (and (= id (get msg "id")) (not (contains? msg "method")))
          (do (println (format "%-24s %5dms %s" method (elapsed-ms t0)
                               (abbreviate (or (get msg "error") (get msg "result")))))
              msg)

          (contains? msg "id")
          (do (println "  <- request " (get msg "method") (abbreviate (get msg "params")))
              (lsp/write-message out {"jsonrpc" "2.0" "id" (get msg "id") "result" nil})
              (recur))

          :else
          (do (println "  <- notified" (get msg "method") (abbreviate (get msg "params")))
              (recur)))))))

(defn- client-capabilities [register]
  {"textDocument" (into {}
                        (for [feature (some-> register (str/split #","))]
                          [feature {"dynamicRegistration" true}]))})

(defn -main [& args]
  (let [{:keys [root file uri language delay hover definition register diagnostic capabilities command]}
        (parse-args args)]
    (when (some nil? [root file uri language (first command)])
      (binding [*out* *err*] (println usage))
      (System/exit 2))
    (let [proc (.start (doto (ProcessBuilder. ^java.util.List (vec command))
                         (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)))
          in (BufferedInputStream. (.getInputStream proc))
          out (.getOutputStream proc)
          notify #(lsp/write-message out {"jsonrpc" "2.0" "method" %1 "params" %2})
          doc {"textDocument" {"uri" uri}}]
      (let [initialized (request in out 1 "initialize" {"processId" nil
                                                        "rootUri" root
                                                        "workspaceFolders" [{"uri" root "name" "root"}]
                                                        "capabilities" (client-capabilities register)})]
        (when capabilities
          (println "  capabilities" (json/write-str (get-in initialized ["result" "capabilities"])))))
      (notify "initialized" {})
      (notify "textDocument/didOpen" {"textDocument" {"uri" uri "languageId" language
                                                       "version" 1 "text" (slurp file)}})
      (when delay (Thread/sleep (Long/parseLong delay)))
      (when hover
        (request in out 2 "textDocument/hover" (assoc doc "position" (position hover))))
      (when definition
        (request in out 3 "textDocument/definition" (assoc doc "position" (position definition))))
      (when diagnostic
        (doseq [{:strs [source code message]} (get-in (request in out 4 "textDocument/diagnostic" doc)
                                                      ["result" "items"])]
          (println "  " source code "-" message)))
      (request in out 5 "shutdown" nil)
      (notify "exit" nil)
      (.close out)
      (println "exit code" (.waitFor proc))
      (System/exit 0))))
