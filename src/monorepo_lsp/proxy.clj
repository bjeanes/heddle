(ns monorepo-lsp.proxy
  "Routes messages between the client and the backends that handle its files.
  See \"Routing messages\" in DESIGN.md.

  The client's messages are handled in order, on the thread that reads them.
  Each backend's messages are handled on that backend's own thread."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [monorepo-lsp.backend :as backend]
            [monorepo-lsp.capabilities :as capabilities]
            [monorepo-lsp.config :as config]
            [monorepo-lsp.log :as log]
            [monorepo-lsp.lsp :as lsp]
            [monorepo-lsp.paths :as paths]
            [monorepo-lsp.translate :as translate])
  (:import (java.net URI)))

(defn create
  "A proxy for one client. `opts` has:

  - :send, a function to call with each message for the client;
  - :connect, the connector for backends (see monorepo-lsp.backend);
  - :server-types, the set of server types to serve, or nil to serve all;
  - :cwd, where to look for the config if no workspace root the client names
    has one."
  [opts]
  (assoc opts :state (atom {:backends {}
                            :documents {}
                            :last-id 0
                            :to-client {}})))

(defn- send-client! [proxy msg]
  ((:send proxy) msg))

(defn- uri->path [uri]
  (try
    (let [uri (URI. uri)]
      (when (= "file" (.getScheme uri))
        (let [path (str/replace (.getPath (.normalize uri)) #"/+$" "")]
          (if (= "" path) "/" path))))
    (catch Exception _ nil)))

(defn- path->uri [path]
  (str (URI. "file" "" path nil)))

(defn- request-client!
  "Sends a request to the client with an ID of the proxy's own, and calls
  `on-response` with the client's response."
  [proxy method params on-response]
  (let [state (swap! (:state proxy)
                     (fn [{:keys [last-id] :as state}]
                       (-> state
                           (assoc :last-id (inc last-id))
                           (assoc-in [:to-client (inc last-id)] on-response))))]
    (send-client! proxy (lsp/request (:last-id state) method params))
    (:last-id state)))

(defn- client-response! [proxy {:strs [id] :as msg}]
  (let [[old] (swap-vals! (:state proxy) update :to-client dissoc id)]
    (if-let [on-response (get-in old [:to-client id])]
      (on-response msg)
      (log/info "the client answered an unknown request" (pr-str id)))))

;; Initialization

(defn- config-root
  "The first directory with a config: the workspace roots the client named in
  `initialize`, then `cwd`."
  [params cwd]
  (let [roots (concat (keep uri->path (cons (get params "rootUri")
                                            (map #(get % "uri") (get params "workspaceFolders"))))
                      [cwd])]
    (or (first (filter #(.exists (io/file % config/file-name)) roots))
        (throw (ex-info (str "no " config/file-name " in " (str/join " or " (distinct roots))) {})))))

(defn- initialize! [proxy {:strs [id params]}]
  (try
    (let [repo (config-root params (:cwd proxy))
          config (config/read-config repo)]
      (doseq [name (:server-types proxy)
              :when (not (contains? (get config "servers") name))]
        (log/info "there's no server type" (pr-str name) "in" (str repo "/" config/file-name)))
      (swap! (:state proxy) assoc :repo repo :config config :client-params params)
      (send-client! proxy (lsp/response id (capabilities/initialize-result (get params "capabilities")))))
    (catch Exception e
      (send-client! proxy (lsp/error-response id -32603 (ex-message e))))))

;; Backends

(defn- document-selector
  "The backend's files: those of its languages in its scope or among its
  fetched files, and those matching its globs."
  [{:keys [config server-type scope repo cache]}]
  (let [{:strs [languages globs]} (get-in config ["servers" server-type])
        relative (fn [dir pattern] {"baseUri" (path->uri dir) "pattern" pattern})]
    (vec (concat (for [language languages
                       dir [(paths/client-path repo scope) cache]]
                   {"language" language "pattern" (relative dir "**/*")})
                 (for [glob globs]
                   {"pattern" (relative repo glob)})))))

(defn- register! [proxy b]
  (when (backend/ready? b)
    (let [client-caps (get-in @(:state proxy) [:client-params "capabilities"])
          registrations (capabilities/registrations client-caps (backend/capabilities b)
                                                    (document-selector b) (:backend-id b))]
      (when (seq registrations)
        (request-client! proxy "client/registerCapability" {"registrations" registrations}
                         (fn [{:strs [error]}]
                           (when error
                             (log/info "the client refused" (:backend-id b) "registrations:"
                                       (get error "message")))))))))

(defn- init-params [b client-params]
  (let [root-uri (path->uri (:root b))]
    (into {}
          (remove (comp nil? val))
          {"processId" (.pid (ProcessHandle/current))
           "clientInfo" (get client-params "clientInfo")
           "locale" (get client-params "locale")
           "rootUri" root-uri
           "rootPath" (:root b)
           "workspaceFolders" [{"uri" root-uri
                                "name" (last (str/split (paths/client-path (:repo b) (:scope b)) #"/"))}]
           "capabilities" (assoc-in (get client-params "capabilities" {})
                                    ["general" "positionEncodings"] ["utf-16"])
           "trace" (get client-params "trace")})))

(declare from-backend!)

(defn- start-backend! [proxy [server-type scope :as k]]
  (let [{:keys [config repo client-params]} @(:state proxy)
        b (backend/backend config server-type scope repo)]
    (log/info "starting" server-type "for" (pr-str scope) "as" (:backend-id b))
    (swap! (:state proxy) assoc-in [:backends k] b)
    (backend/start! b (:connect proxy) (init-params b client-params) #(from-backend! proxy b %))
    (backend/when-ready b #(register! proxy b))
    b))

(defn- backend-for! [proxy k]
  (or (get-in @(:state proxy) [:backends k])
      (start-backend! proxy k)))

(defn- from-backend! [proxy b msg]
  (if (nil? msg)
    (do (log/info (:backend-id b) "stopped")
        (swap! (:state proxy) update :backends
               (fn [backends]
                 (let [k [(:server-type b) (:scope b)]]
                   (cond-> backends (identical? b (get backends k)) (dissoc k))))))
    (log/info (:backend-id b) "sent" (get msg "method") "which isn't handled yet")))

;; Documents

(defn- repo-relative [repo path]
  (when (and path (str/starts-with? path (str repo "/")))
    (subs path (inc (count repo)))))

(defn- fetched-by
  "The backend that `path` is a fetched copy for, if any."
  [backends path]
  (first (for [[k b] backends
               :when (and path (str/starts-with? path (str (:cache b) "/")))]
           k)))

(defn- handlers
  "The [server-type scope] of each backend that handles the file at `uri` when
  it's opened as `language`, in sorted order."
  [proxy uri language]
  (let [{:keys [config repo backends]} @(:state proxy)
        only (:server-types proxy)
        path (uri->path uri)]
    (if-let [k (fetched-by backends path)]
      [k]
      (when-let [path (repo-relative repo path)]
        (sort (for [server-type (config/server-types config path language)
                    :when (or (nil? only) (contains? only server-type))
                    :let [scope (config/scope-for config server-type path)]
                    :when scope]
                [server-type scope]))))))

(defn- running-handlers
  "The running backends that handle the open file at `uri`."
  [proxy uri]
  (let [{:keys [documents backends]} @(:state proxy)]
    (keep backends (handlers proxy uri (get-in documents [uri :language])))))

(defn- notify-backend! [b method params]
  (let [params (translate/to-server b params)]
    (backend/when-ready b #(when (backend/ready? b)
                             (backend/notify! b method params)))))

(defn- open! [proxy {:strs [textDocument] :as params}]
  (let [{:strs [uri languageId]} textDocument]
    (swap! (:state proxy) assoc-in [:documents uri] {:language languageId})
    (doseq [k (handlers proxy uri languageId)]
      (notify-backend! (backend-for! proxy k) "textDocument/didOpen" params))))

(defn- document-notification! [proxy method params]
  (let [uri (get-in params ["textDocument" "uri"])]
    (doseq [b (running-handlers proxy uri)]
      (notify-backend! b method params))
    (when (= "textDocument/didClose" method)
      (swap! (:state proxy) update :documents dissoc uri))))

;; The client's messages

(defn- client-request! [proxy {:strs [id method] :as msg}]
  (cond
    (= "initialize" method) (initialize! proxy msg)
    (nil? (:config @(:state proxy))) (send-client! proxy (lsp/error-response id -32002 "not initialized"))
    :else (send-client! proxy (lsp/response id (capabilities/empty-result method)))))

(defn- client-notification! [proxy {:strs [method params]}]
  (when (:config @(:state proxy))
    (case method
      "textDocument/didOpen" (open! proxy params)
      ("textDocument/didChange" "textDocument/didSave" "textDocument/didClose")
      (document-notification! proxy method params)
      nil)))

(defn handle-client!
  "Handles one message from the client."
  [proxy msg]
  (cond
    (lsp/response? msg) (client-response! proxy msg)
    (contains? msg "id") (client-request! proxy msg)
    :else (client-notification! proxy msg)))
