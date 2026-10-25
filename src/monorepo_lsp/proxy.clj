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
            [monorepo-lsp.fetched :as fetched]
            [monorepo-lsp.log :as log]
            [monorepo-lsp.lsp :as lsp]
            [monorepo-lsp.merge :as merge]
            [monorepo-lsp.origin :as origin]
            [monorepo-lsp.paths :as paths]
            [monorepo-lsp.translate :as translate])
  (:import (java.net URI)))

(defn create
  "A proxy for one client. `opts` has:

  - :send, a function to call with each message for the client;
  - :connect, the connector for backends (see monorepo-lsp.backend);
  - :server-types, the set of server types to serve, or nil to serve all;
  - :eager, whether to start every backend when the client initializes, and
    wait up to :eager-timeout-ms (by default `eager-timeout-ms`) for them
    before answering;
  - :cwd, where to look for the config if no workspace root the client names
    has one;
  - :exit, a function to call with the exit code once the client sends
    `exit`;
  - :clock, optionally, a function that returns the time in milliseconds."
  [opts]
  (assoc opts :state (atom {:backends {}
                            :documents {}
                            :last-id 0
                            :to-client {}
                            :progress {}
                            :registered {}
                            :reported {}})))

(defn- now-ms [proxy]
  ((or (:clock proxy) #(System/currentTimeMillis))))

(defn- send-client!
  "Sends `msg` to the client, or holds it under :held while the proxy is yet
  to answer `initialize`."
  [proxy msg]
  (locking (:state proxy)
    (if (:held @(:state proxy))
      (swap! (:state proxy) update :held conj msg)
      ((:send proxy) msg))))

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
  `on-response` with the client's response. `origin` is the backend and ID of
  the request this one forwards, if any."
  [proxy method params origin on-response]
  (let [state (swap! (:state proxy)
                     (fn [{:keys [last-id] :as state}]
                       (-> state
                           (assoc :last-id (inc last-id))
                           (assoc-in [:to-client (inc last-id)] {:origin origin
                                                                 :on-response on-response}))))]
    (send-client! proxy (lsp/request (:last-id state) method params))))

(defn- client-response! [proxy {:strs [id] :as msg}]
  (let [[old] (swap-vals! (:state proxy) update :to-client dissoc id)]
    (if-let [{:keys [on-response]} (get-in old [:to-client id])]
      (on-response msg)
      (log/info "the client answered an unknown request" (pr-str id)))))

(defn- cancel-client-request!
  "Passes on a backend's cancellation of a request it sent the client."
  [proxy origin]
  (doseq [[id request] (:to-client @(:state proxy))
          :when (= origin (:origin request))]
    (send-client! proxy (lsp/notification "$/cancelRequest" {"id" id}))))

(def ^:private message-type {:error 1 :warning 2})

(defn- show-message!
  "Shows `message` to the user and logs it."
  [proxy type message]
  (send-client! proxy (lsp/notification "window/showMessage" {"type" (message-type type)
                                                              "message" message}))
  (log/info message))

;; Initialization

(defn- config-roots
  "Where to look for a config: the workspace roots the client named in
  `initialize`, then `cwd`."
  [params cwd]
  (distinct (concat (keep uri->path (cons (get params "rootUri")
                                          (map #(get % "uri") (get params "workspaceFolders"))))
                    [cwd])))

(def ^:private no-config {"servers" {} "scopes" {} "runners" {}})

(defn- load-config
  "The repo and its config, from the first of `roots` with a config. Without a
  usable config, the proxy serves nothing, and :problem has the type and text
  of a message for the user."
  [roots]
  (if-let [repo (first (filter #(.exists (io/file % config/file-name)) roots))]
    (try
      {:repo repo :config (config/read-config repo)}
      (catch Exception e
        {:repo repo
         :config no-config
         :problem [:error (str (ex-message e) "\nmonorepo-lsp is serving nothing until the config is fixed and it restarts.")]}))
    {:repo (first roots)
     :config no-config
     :problem [:warning (str "There's no " config/file-name " in " (str/join " or " roots) "."
                             "\nmonorepo-lsp is serving nothing until there is one and it restarts.")]}))

(declare start-backend! ready-backends register! when-all-ready)

(def ^:private eager-timeout-ms 30000)

(defn- start-every-backend!
  "Starts a backend for every server type the proxy serves in every scope, and
  waits until they have all started or failed, or the timeout runs out."
  [proxy]
  (let [{:keys [config]} @(:state proxy)
        only (:server-types proxy)
        started (promise)]
    (when-all-ready (doall (for [[scope entries] (sort-by key (get config "scopes"))
                                 server-type (sort (keys entries))
                                 :when (or (nil? only) (contains? only server-type))]
                             (start-backend! proxy [server-type scope])))
      #(deliver started true))
    (deref started (or (:eager-timeout-ms proxy) eager-timeout-ms) nil)))

(defn- answer-initialize!
  "Sends the client the answer to `initialize`, then everything held back
  while the proxy was yet to send it."
  [proxy response]
  (locking (:state proxy)
    (let [[old] (swap-vals! (:state proxy) dissoc :held)]
      ((:send proxy) response)
      (run! (:send proxy) (:held old)))))

(defn- initialize! [proxy {:strs [id params]}]
  (let [{:keys [repo config problem]} (load-config (config-roots params (:cwd proxy)))
        eager? (and (:eager proxy) (not problem))]
    (when-not problem
      (doseq [name (:server-types proxy)
              :when (not (contains? (get config "servers") name))]
        (log/info "there's no server type" (pr-str name) "in" (str repo "/" config/file-name))))
    (swap! (:state proxy) assoc :repo repo :config config :client-params params :held (when eager? []))
    (when eager?
      (start-every-backend! proxy))
    (let [result (capabilities/initialize-result (get params "capabilities") (ready-backends proxy))]
      (swap! (:state proxy) assoc :announced (capabilities/announced-methods (get result "capabilities")))
      (answer-initialize! proxy (lsp/response id result)))
    (register! proxy)
    (when-let [[type message] problem]
      (show-message! proxy type message))))

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

(defn- running-backends
  "Every running backend, in priority order."
  [proxy]
  (let [{:keys [config backends]} @(:state proxy)
        rank (zipmap (config/by-priority config (set (map first (keys backends)))) (range))]
    (map backends (sort-by (fn [[server-type scope]] [(rank server-type) scope]) (keys backends)))))

(defn- server-capabilities
  "The server capabilities of the backend `b`, with its commands qualified
  with its server type."
  [b]
  (cond-> (backend/capabilities b)
    (get-in (backend/capabilities b) ["executeCommandProvider" "commands"])
    (update-in ["executeCommandProvider" "commands"]
               #(mapv (partial origin/qualify-command (:server-type b)) %))))

(defn- ready-backends
  "The server capabilities and document selector of each running backend that
  has started, in priority order."
  [proxy]
  (for [b (running-backends proxy)
        :when (backend/ready? b)]
    [(server-capabilities b) (document-selector b)]))

(defn- ask-client!
  "Sends the client a request about registrations, and logs it if the client
  refuses."
  [proxy method params]
  (request-client! proxy method params nil
                   (fn [{:strs [error]}]
                     (when error
                       (log/info "the client refused" method "-" (get error "message"))))))

(defn- register!
  "Registers each feature that any ready backend offers, with one registration
  covering all of their files. Some clients, such as VS Code, ask every
  registration that matches a file, so a registration per backend would bring
  the proxy each request several times. When a registration has to change, the
  proxy unregisters it and registers it again. Features announced in the
  answer to `initialize` aren't registered, and nothing is until it's sent."
  [proxy]
  (locking (:state proxy)
    (let [{:keys [client-params registered announced held]} @(:state proxy)
          wanted (when-not held
                   (remove #(contains? announced (get % "method"))
                           (capabilities/registrations (get client-params "capabilities")
                                                       (ready-backends proxy))))
          changed (remove #(= % (get registered (get % "method"))) wanted)
          replaced (filter #(contains? registered (get % "method")) changed)]
      (swap! (:state proxy) update :registered into (map (juxt #(get % "method") identity)) changed)
      (when (seq replaced)
        (ask-client! proxy "client/unregisterCapability"
                     {"unregisterations" (mapv #(select-keys % ["id" "method"]) replaced)}))
      (when (seq changed)
        (ask-client! proxy "client/registerCapability" {"registrations" (vec changed)})))))

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

(defn- describe [{:keys [server-type scope]}]
  (str server-type " in " (if (= "." scope) "the repo root" scope)))

(defn- elapsed?
  "Whether `ms` or more have passed between `since` and `now`, or there's no
  `since`."
  [since now ms]
  (or (nil? since) (<= ms (- now since))))

(def ^:private report-interval-ms 60000)

(defn- report!
  "Shows the user a message about the backend `b`, unless one was shown for its
  server type and scope in the last `report-interval-ms`, as a backend that
  keeps failing would otherwise flood them."
  [proxy b type message]
  (let [k [(:server-type b) (:scope b)]
        now (now-ms proxy)
        due? #(elapsed? (get-in % [:reported k]) now report-interval-ms)
        [old] (swap-vals! (:state proxy) #(cond-> % (due? %) (assoc-in [:reported k] now)))]
    (if (due? old)
      (show-message! proxy type message)
      (log/info message))))

(declare from-backend! failed!)

(defn- start-backend! [proxy [server-type scope :as k]]
  (let [{:keys [config repo client-params]} @(:state proxy)
        b (backend/backend config server-type scope repo)]
    (log/info "starting" server-type "for" (pr-str scope) "as" (:backend-id b))
    (swap! (:state proxy) assoc-in [:backends k] b)
    (backend/start! b (:connect proxy) (init-params b client-params) #(from-backend! proxy b %))
    (backend/when-ready b #(if (backend/ready? b)
                             (register! proxy)
                             (do (failed! proxy b :error (str "failed to start: " (backend/error b)))
                                 (future (backend/exit! b 2000)))))
    b))

(def ^:private retry-interval-ms 30000)

(defn- backend-for!
  "The backend for `k`, a [server-type scope], started if it isn't running.
  Returns nil if it failed within the last `retry-interval-ms`."
  [proxy k]
  (let [{:keys [backends failures]} @(:state proxy)]
    (cond
      (contains? backends k) (get backends k)
      (elapsed? (get-in failures [k :at]) (now-ms proxy) retry-interval-ms) (start-backend! proxy k))))

(defn- to-client!
  "Translates a payload from the backend `b` for the client, after fetching
  any files it refers to that only the backend can see."
  [b payload]
  (doseq [[file problem] (fetched/fetch! b (translate/fetched-files b payload))]
    (log/info "couldn't fetch" file "from" (:backend-id b) "-" problem))
  (translate/to-client b payload))

(defn- set-diagnostics
  "`by-file`, which holds each backend's latest diagnostics for each file,
  with the backend `backend-id`'s diagnostics for `uri` replaced."
  [by-file uri backend-id diagnostics]
  (let [for-file (if (seq diagnostics)
                   (assoc (get by-file uri) backend-id diagnostics)
                   (dissoc (get by-file uri) backend-id))]
    (if (seq for-file)
      (assoc by-file uri for-file)
      (dissoc by-file uri))))

(defn- publish-diagnostics!
  "Replaces the backend `b`'s diagnostics for the file at `uri`, and publishes
  every backend's diagnostics for it together. Each publication replaces what
  the client had for the file, so forwarding a backend's own would hide the
  others'."
  [proxy b {:strs [uri diagnostics version]}]
  (locking (:state proxy)
    (let [state (swap! (:state proxy) update :diagnostics set-diagnostics uri (:backend-id b) diagnostics)]
      (send-client! proxy (lsp/notification "textDocument/publishDiagnostics"
                                            (cond-> {"uri" uri
                                                     "diagnostics" (vec (apply concat (vals (get-in state [:diagnostics uri]))))}
                                              version (assoc "version" version)))))))

(defn- clear-diagnostics!
  "Withdraws the diagnostics of the backend `b`, which has stopped."
  [proxy b]
  (doseq [[uri for-file] (:diagnostics @(:state proxy))
          :when (contains? for-file (:backend-id b))]
    (publish-diagnostics! proxy b {"uri" uri "diagnostics" []})))

(defn- failed!
  "Replaces the backend `b`, which failed to start or stopped, with a record of
  why and when under :failures, withdraws its diagnostics and tells the user.
  `what-happened` follows the backend's description in the message."
  [proxy b type what-happened]
  (let [k [(:server-type b) (:scope b)]
        now (now-ms proxy)
        message (str (describe b) " " what-happened ". Opening one of its files will start it again, once "
                     (quot retry-interval-ms 1000) " s have passed.")
        [old] (swap-vals! (:state proxy)
                          (fn [state]
                            (cond-> state
                              (identical? b (get-in state [:backends k]))
                              (-> (update :backends dissoc k)
                                  (assoc-in [:failures k] {:message message :at now})))))]
    (when (identical? b (get-in old [:backends k]))
      (clear-diagnostics! proxy b)
      (when-not (or (:shut-down old) (:exiting old))
        (report! proxy b type message)))))

(defn- stopped!
  "Handles the backend `b` stopping. A backend that stops while starting has
  failed to start instead, which is handled once it settles."
  [proxy b]
  (log/info (:backend-id b) "stopped")
  (when (= :exited (backend/status b))
    (failed! proxy b :warning (str "stopped" (some->> (backend/exit-code b 1000) (str " with exit code "))))))

(defn- qualify
  "An ID that the backend `b` chose for the client, made unique among all
  backends. Two backends of one server type will often choose the same IDs."
  [b id]
  (str (:backend-id b) "/" id))

(defn- qualify-ids [b registrations]
  (mapv #(update % "id" (partial qualify b)) registrations))

(defn- qualify-registered-commands [b registrations]
  (mapv (fn [registration]
          (cond-> registration
            (get-in registration ["registerOptions" "commands"])
            (update-in ["registerOptions" "commands"]
                       #(mapv (partial origin/qualify-command (:server-type b)) %))))
        registrations))

(defn- progress-token!
  "The token to give the client for one that the backend `b` created. The
  client's `window/workDoneProgress/cancel` finds the backend by it."
  [proxy b token]
  (let [qualified (qualify b token)]
    (swap! (:state proxy) assoc-in [:progress qualified] {:backend b :token token})
    qualified))

(defn- request-params
  "The params of a request from the backend `b`, with the registration IDs,
  commands and progress tokens it chose qualified."
  [proxy b method params]
  (case method
    "window/workDoneProgress/create" (update params "token" #(progress-token! proxy b %))
    "client/registerCapability" (update params "registrations"
                                        #(->> % (qualify-ids b) (qualify-registered-commands b)))
    "client/unregisterCapability" (update params "unregisterations" #(qualify-ids b %))
    params))

(defn- progress-params
  "The params of `$/progress` from the backend `b`. A token the backend created
  is qualified, and a token the client chose is left as it is."
  [proxy b {:strs [token value] :as params}]
  (let [qualified (qualify b token)]
    (if (contains? (:progress @(:state proxy)) qualified)
      (do (when (= "end" (get value "kind"))
            (swap! (:state proxy) update :progress dissoc qualified))
          (assoc params "token" qualified))
      params)))

(defn- limit-selectors
  "The params of `client/registerCapability` from the backend `b`, as client
  paths, with each document selector limited to the backend's files."
  [b params]
  (update params "registrations"
          (fn [registrations]
            (mapv #(cond-> %
                     (contains? (get % "registerOptions") "documentSelector")
                     (update-in ["registerOptions" "documentSelector"]
                                capabilities/limit-selector (document-selector b)))
                  registrations))))

(defn- from-backend! [proxy b {:strs [id method params] :as msg}]
  (cond
    (nil? msg)
    (stopped! proxy b)

    (contains? msg "id")
    (request-client! proxy method (cond->> (translate/to-client b (request-params proxy b method params))
                                    (= "client/registerCapability" method) (limit-selectors b))
                     [(:backend-id b) id]
                     (fn [response]
                       (backend/send! b (assoc (translate/to-server b (dissoc response "id")) "id" id))))

    (= "$/cancelRequest" method)
    (cancel-client-request! proxy [(:backend-id b) (get params "id")])

    (= "$/progress" method)
    (send-client! proxy (lsp/notification method (translate/to-client b (progress-params proxy b params))))

    (= "textDocument/publishDiagnostics" method)
    (publish-diagnostics! proxy b (translate/to-client b params))

    :else
    (send-client! proxy (lsp/notification method (translate/to-client b params)))))

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
  it's opened as `language`, in priority order."
  [proxy uri language]
  (let [{:keys [config repo backends]} @(:state proxy)
        only (:server-types proxy)
        path (uri->path uri)]
    (if-let [k (fetched-by backends path)]
      [k]
      (when-let [path (repo-relative repo path)]
        (for [server-type (config/by-priority config (config/server-types config path language))
              :when (or (nil? only) (contains? only server-type))
              :let [scope (config/scope-for config server-type path)]
              :when scope]
          [server-type scope])))))

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
    (doseq [k (handlers proxy uri languageId)
            :let [b (backend-for! proxy k)]
            :when b]
      (notify-backend! b "textDocument/didOpen" params))))

(defn- document-notification! [proxy method params]
  (let [uri (get-in params ["textDocument" "uri"])]
    (doseq [b (running-handlers proxy uri)]
      (notify-backend! b method params))
    (when (= "textDocument/didClose" method)
      (swap! (:state proxy) update :documents dissoc uri))))

;; The workspace

(defn- notify-all! [proxy method params]
  (doseq [b (running-backends proxy)]
    (notify-backend! b method params)))

(defn- watched-files-changed!
  "Passes on each change to the backends whose scopes contain the file."
  [proxy {:strs [changes]}]
  (let [repo (:repo @(:state proxy))
        within? (fn [b change]
                  (some->> (uri->path (get change "uri")) (repo-relative repo) (paths/within? (:scope b))))]
    (doseq [b (running-backends proxy)
            :let [theirs (filterv #(within? b %) changes)]
            :when (seq theirs)]
      (notify-backend! b "workspace/didChangeWatchedFiles" {"changes" theirs}))))

;; Requests

(def ^:private methods-with-commands
  #{"textDocument/completion" "completionItem/resolve"
    "textDocument/codeAction" "codeAction/resolve"
    "textDocument/codeLens" "codeLens/resolve"
    "textDocument/inlayHint" "inlayHint/resolve"})

(defn- result-for-client
  "The result of the request `method` from the backend `b`, ready for the
  client."
  [b method result]
  (let [result (cond->> (to-client! b result)
                 (contains? methods-with-commands method) (origin/qualify-commands (:server-type b)))]
    (origin/mark-result method (:backend-id b) result)))

(defn- forward-request! [proxy b {:strs [id method params]}]
  (backend/request! b method (translate/to-server b params) id
                    (fn [response]
                      (send-client! proxy
                                    (cond
                                      (nil? response)
                                      (lsp/error-response id -32603 (str (:server-type b) " stopped before answering"))

                                      (contains? response "error")
                                      (assoc (to-client! b response) "id" id)

                                      :else
                                      (lsp/response id (result-for-client b method (get response "result"))))))))

(def ^:private request-failed -32803)

(defn- failures
  "Why each of the backends `ks`, as [server-type scope], failed, for those
  that aren't running because they did."
  [{:keys [backends failures]} ks]
  (keep #(when-not (contains? backends %) (get-in failures [% :message])) ks))

(defn- failed-response
  "An error answering the request `id`, which an agent sees in its tool's
  result, with why the backends that would have answered it failed."
  [id reasons]
  (lsp/error-response id request-failed (str/join "\n" reasons)))

(defn- answer-unanswered!
  "Answers a request that no running backend can, with an empty result. If no
  backend that would handle the request's file is running, and some have
  failed, the answer is an error saying why instead."
  [proxy {:strs [id method params]}]
  (let [state @(:state proxy)
        uri (get-in params ["textDocument" "uri"])
        ks (handlers proxy uri (get-in state [:documents uri :language]))
        reasons (when (not-any? #(contains? (:backends state) %) ks)
                  (failures state ks))]
    (send-client! proxy (if (seq reasons)
                          (failed-response id reasons)
                          (lsp/response id (capabilities/empty-result method))))))

(defn- route-request!
  "Sends a request to the first of the backends `b` and `more`, in priority
  order, that offers the request's feature, or answers it with
  `answer-unanswered!` if none does. A request for a feature the proxy doesn't
  know about goes to the first backend that has started."
  [proxy {:strs [method] :as msg} [b & more]]
  (if (nil? b)
    (answer-unanswered! proxy msg)
    (backend/when-ready b
      #(let [feature (capabilities/feature method)]
         (if (and (backend/ready? b)
                  (or (nil? feature) (capabilities/offers? (backend/capabilities b) feature)))
           (forward-request! proxy b msg)
           (route-request! proxy msg more))))))

(defn- when-all-ready
  "Calls `f` once every one of `backends` has started or failed to start."
  [backends f]
  (if-let [[b & more] (seq backends)]
    (backend/when-ready b #(when-all-ready more f))
    (f)))

(defn- ask-all!
  "Sends the request to each of `backends`, and calls `on-responses` with
  their responses in the same order once they have all answered. A backend
  that stops first answers nil."
  [backends {:strs [id method params]} on-responses]
  (let [n (count backends)
        responses (atom {})]
    (doseq [[i b] (map-indexed vector backends)]
      (backend/request! b method (translate/to-server b params) id
                        (fn [response]
                          (let [all (swap! responses assoc i response)]
                            (when (= n (count all))
                              (on-responses (mapv all (range n))))))))))

(defn- answered? [response]
  (and response (not (contains? response "error"))))

(defn- combined-response
  "The response to the client's request `id` from the backends' `responses`.
  Backends that failed to answer are left out, unless they all did."
  [id method backends responses]
  (let [answers (for [[b response] (map vector backends responses)
                      :when (answered? response)]
                  [(:server-type b) (result-for-client b method (get response "result"))])]
    (doseq [[b response] (map vector backends responses)
            :when (not (answered? response))]
      (log/info (:backend-id b) "didn't answer" method "-"
                (get-in response ["error" "message"] "it stopped")))
    (cond
      (seq answers) (lsp/response id (merge/combine method answers))
      (some some? responses) (assoc (first (filter some? responses)) "id" id)
      :else (lsp/error-response id -32603 "every backend stopped before answering"))))

(defn- combine-request!
  "Sends a request to every one of `backends` that offers the request's
  feature, and answers with their combined results."
  [proxy {:strs [id method] :as msg} backends]
  (when-all-ready backends
    #(let [feature (capabilities/feature method)
           offering (filterv (fn [b] (and (backend/ready? b)
                                          (capabilities/offers? (backend/capabilities b) feature)))
                             backends)]
       (if (empty? offering)
         (answer-unanswered! proxy msg)
         (ask-all! offering msg
                   (fn [responses]
                     (send-client! proxy (combined-response id method offering responses))))))))

(defn- backend-by-id [proxy backend-id]
  (first (filter #(= backend-id (:backend-id %)) (vals (:backends @(:state proxy))))))

(defn- resolve!
  "Sends a request to resolve an item to the backend that made it. The item
  comes back unchanged if that backend has stopped or doesn't resolve items."
  [proxy {:strs [id method params] :as msg}]
  (let [[backend-id item] (origin/unmark params)
        b (some->> backend-id (backend-by-id proxy))]
    (if (and b (backend/ready? b) (capabilities/resolves? (backend/capabilities b) method))
      (forward-request! proxy b (assoc msg "params" item))
      (send-client! proxy (lsp/response id params)))))

(defn- command-backend
  "The running backend of `server-type` to run a command with `arguments`.
  Where there are several, the one with the deepest scope that contains a
  file the arguments refer to, or else the first by scope."
  [proxy server-type arguments]
  (let [{:keys [repo backends]} @(:state proxy)
        running (sort-by :scope (filter #(= server-type (:server-type %)) (vals backends)))
        files (keep #(repo-relative repo (uri->path %))
                    (filter string? (tree-seq coll? seq arguments)))
        containing (filter (fn [b] (some #(paths/within? (:scope b) %) files)) running)]
    (or (last (sort-by (comp count :scope) containing))
        (first running))))

(defn- not-running-response
  "The answer to the request `id` for a command of `server-type`, which has no
  running backend to run it."
  [proxy id server-type]
  (let [state @(:state proxy)
        reasons (failures state (sort (filter #(= server-type (first %)) (keys (:failures state)))))]
    (if (seq reasons)
      (failed-response id reasons)
      (lsp/error-response id -32603 (str "no " server-type " backend is running")))))

(defn- execute-command!
  "Sends `workspace/executeCommand` to a backend of the server type that the
  command is qualified with, as the command ID the backend chose. If none is
  running, the answer is an error saying why."
  [proxy {:strs [id params] :as msg}]
  (let [server-types (keys (get-in @(:state proxy) [:config "servers"]))
        [server-type command] (origin/command-origin server-types (get params "command"))
        b (when server-type (command-backend proxy server-type (get params "arguments")))
        not-running #(send-client! proxy (not-running-response proxy id server-type))]
    (cond
      (nil? server-type)
      (send-client! proxy (lsp/error-response id -32602 (str "unknown command " (pr-str (get params "command")))))

      (nil? b)
      (not-running)

      :else
      (backend/when-ready b #(if (backend/ready? b)
                               (forward-request! proxy b (assoc-in msg ["params" "command"] command))
                               (not-running))))))

(defn- cancel!
  "Passes on the client's cancellation of a request it sent a backend."
  [proxy client-id]
  (doseq [b (vals (:backends @(:state proxy)))
          :let [id (backend/tagged-request b client-id)]
          :when id]
    (backend/notify! b "$/cancelRequest" {"id" id})))

(defn- cancel-progress!
  "Passes on the client's cancellation of progress a backend created."
  [proxy token]
  (when-let [{:keys [backend token]} (get-in @(:state proxy) [:progress token])]
    (backend/notify! backend "window/workDoneProgress/cancel" {"token" token})))

(def ^:private shutdown-timeout-ms 10000)

(defn- shutdown!
  "Answers `shutdown` once every backend has, or once they've had
  `shutdown-timeout-ms` in all. The client's thread doesn't wait, since a
  backend may need answers from the client before it can shut down."
  [proxy id]
  (swap! (:state proxy) assoc :shut-down true)
  (let [backends (vals (:backends @(:state proxy)))
        answers (mapv backend/shutdown! backends)
        deadline (+ (System/currentTimeMillis) shutdown-timeout-ms)]
    (future
      (doseq [[b answered] (map vector backends answers)
              :let [ms-left (max 0 (- deadline (System/currentTimeMillis)))]]
        (when (= ::timeout (deref answered ms-left ::timeout))
          (log/info (:backend-id b) "didn't answer shutdown in time")))
      (send-client! proxy (lsp/response id nil)))))

(defn- exit! [proxy]
  (swap! (:state proxy) assoc :exiting true)
  (run! deref (mapv #(future (backend/exit! % 2000)) (vals (:backends @(:state proxy)))))
  ((:exit proxy) (if (:shut-down @(:state proxy)) 0 1)))

;; The client's messages

(defn- client-request! [proxy {:strs [id method params] :as msg}]
  (let [uri (get-in params ["textDocument" "uri"])]
    (cond
      (= "initialize" method) (initialize! proxy msg)
      (nil? (:config @(:state proxy))) (send-client! proxy (lsp/error-response id -32002 "not initialized"))
      (= "shutdown" method) (shutdown! proxy id)
      (capabilities/resolve-method? method) (resolve! proxy msg)
      (= "workspace/executeCommand" method) (execute-command! proxy msg)
      (= "workspace/symbol" method) (combine-request! proxy msg (running-backends proxy))
      (and uri (capabilities/combined? method)) (combine-request! proxy msg (running-handlers proxy uri))
      uri (route-request! proxy msg (running-handlers proxy uri))
      :else (send-client! proxy (lsp/response id nil)))))

(defn- client-notification! [proxy {:strs [method params]}]
  (cond
    (= "exit" method) (exit! proxy)
    (nil? (:config @(:state proxy))) nil
    :else (case method
            "textDocument/didOpen" (open! proxy params)
            ("textDocument/didChange" "textDocument/didSave" "textDocument/didClose")
            (document-notification! proxy method params)
            "workspace/didChangeConfiguration" (notify-all! proxy method params)
            "workspace/didChangeWatchedFiles" (watched-files-changed! proxy params)
            "$/cancelRequest" (cancel! proxy (get params "id"))
            "window/workDoneProgress/cancel" (cancel-progress! proxy (get params "token"))
            nil)))

(defn handle-client!
  "Handles one message from the client."
  [proxy msg]
  (cond
    (lsp/response? msg) (client-response! proxy msg)
    (contains? msg "id") (client-request! proxy msg)
    :else (client-notification! proxy msg)))
