(ns heddle.proxy
  "Routes messages between the client and the backends that handle its files.
  See \"Routing messages\" in DESIGN.md.

  The client's messages are handled in order, on the thread that reads them.
  Each backend's messages are handled on that backend's own thread."
  (:require [clojure.string :as str]
            [heddle.backend :as backend]
            [heddle.capabilities :as capabilities]
            [heddle.channel :as channel]
            [heddle.config :as config]
            [heddle.diagnostics :as diagnostics]
            [heddle.documents :as documents]
            [heddle.fetched :as fetched]
            [heddle.log :as log]
            [heddle.lsp :as lsp]
            [heddle.merge :as merge]
            [heddle.methods :as methods]
            [heddle.normalise :as normalise]
            [heddle.origin :as origin]
            [heddle.paths :as paths]
            [heddle.registry :as registry]
            [heddle.routing :as routing]
            [heddle.session :as session]
            [heddle.spec :as spec]
            [heddle.translate :as translate]))

(def ^:private eager-timeout-ms 30000) ; with :eager, for every backend to start before answering `initialize`
(def ^:private shutdown-timeout-ms 10000) ; for every backend to answer `shutdown`
(def ^:private exit-timeout-ms 2000) ; for a backend to exit before it's killed

(def ^:private merged-method "workspace/executeCommand") ; whose registrations from backends join the proxy's own
(def ^:private registration-lists {"client/registerCapability" "registrations"
                                   "client/unregisterCapability" "unregisterations"})

(defn create
  "A proxy for one client. `opts` has:

  - :send, a function to call with each message for the client;
  - :connect, the connector for backends (see heddle.backend);
  - :server-types, the set of server types to serve, or nil to serve all;
  - :eager, whether to start every backend when the client initializes, and
    wait up to :eager-timeout-ms (by default `eager-timeout-ms`) for them
    before answering;
  - :start-timeout-ms and :read-timeout-ms, optionally, to override the
    backends' `default-timeouts` (see heddle.backend);
  - :config-file, the config to read, or nil to read the repo's own;
  - :cwd, where to look for the config if no workspace root the client names
    has one, or the repo if the client names none and there's a :config-file;
  - :exit, a function to call with the exit code once the client sends
    `exit`;
  - :clock, optionally, a function that returns the time in milliseconds."
  [opts]
  (assoc opts
         :channel (channel/channel (:send opts))
         :state (atom {;; :uninitialized, then :serving, :shutting-down and :exiting
                       :phase :uninitialized
                       ;; fixed once `initialize` is answered
                       :session nil
                       ;; the backends by [server-type scope], each with the
                       ;; URIs it has been sent `didOpen` for, and those that failed
                       :registry registry/empty-registry
                       ;; what the client has open, and what it has been told
                       :client {:documents {}
                                :announced #{}
                                :announced-commands #{}
                                :registered {}
                                ;; what each backend registered itself, by
                                ;; backend ID then qualified registration ID
                                :registered-by {}
                                :diagnostics {}
                                :progress {}}})))

(defn- now-ms [proxy]
  ((or (:clock proxy) #(System/currentTimeMillis))))

(defn- in-background
  "Calls `f` on a thread of its own, as `future` does. If `f` throws, this
  logs that the proxy couldn't do `what`. The future's value is then nil."
  [what f]
  (future
    (try
      (f)
      (catch Exception e
        (log/info "couldn't" what "-" (ex-message e))))))

(defn- send-client! [proxy msg]
  (channel/send! (:channel proxy) msg))

(defn- request-client!
  "Sends a request to the client with an ID of the proxy's own, and calls
  `on-response` with the client's response. `origin` is the backend and ID of
  the request this one forwards, if any."
  [proxy method params origin on-response]
  (channel/request! (:channel proxy) method params origin on-response))

(defn- client-response! [proxy {:strs [id] :as msg}]
  (when-not (channel/answered! (:channel proxy) msg)
    (log/info "the client answered an unknown request" (pr-str id))))

(defn- cancel-client-request!
  "Passes on a backend's cancellation of a request it sent the client."
  [proxy origin]
  (doseq [id (channel/tagged-requests (:channel proxy) origin)]
    (send-client! proxy (lsp/notification "$/cancelRequest" {"id" id}))))

(def ^:private message-type {:error 1 :warning 2})

(defn- show-message!
  "Shows `message` to the user and logs it."
  [proxy type message]
  (send-client! proxy (lsp/notification "window/showMessage" {"type" (message-type type)
                                                              "message" message}))
  (log/info message))

;; Initialisation

(declare start-backend! ready-backends register! when-all-settled)

(defn- start-every-backend!
  "Starts a backend for every slot the proxy has a spec for, by scope then
  server type, and waits until they have all started or failed, or the
  timeout runs out."
  [proxy]
  (let [specs (get-in @(:state proxy) [:session :specs])
        started (promise)]
    (when-all-settled (doall (for [k (sort-by (fn [[server-type scope]] [scope server-type]) (keys specs))]
                               (start-backend! proxy k)))
      (fn [_] (deliver started true)))
    (deref started (or (:eager-timeout-ms proxy) eager-timeout-ms) nil)))

(defn- start-session!
  "Starts serving the client that sent `initialize` with `params`, from the
  repo and config `loaded`. Returns the :result to answer with, and the
  :messages to show the user afterwards, each as [type message]."
  [proxy params {:keys [repo source config cache-root problem warnings]}]
  (let [specs (spec/specs config repo cache-root (:server-types proxy))
        eager? (and (:eager proxy) (not problem))
        unknown (when-not problem (seq (config/unknown-server-types config (sort (:server-types proxy)))))
        unknown-warning (when unknown
                          (config/unknown-server-types-message source unknown))
        warnings (cond-> warnings unknown-warning (conj unknown-warning))]
    (when unknown-warning
      (log/info unknown-warning))
    (swap! (:state proxy) assoc
           :phase :serving
           :session {:repo repo :config config :specs specs :client-params params})
    (when eager?
      (start-every-backend! proxy))
    (let [result (capabilities/initialize-result (get params "capabilities") (ready-backends proxy))
          caps (get result "capabilities")]
      (swap! (:state proxy) update :client assoc
             :announced (capabilities/announced-methods caps)
             :announced-commands (set (get-in caps ["executeCommandProvider" "commands"])))
      {:result result
       :messages (concat (when-let [[type reason until] problem]
                           [[type (str reason "\nheddle is serving nothing until " until " and it restarts.")]])
                         (for [warning warnings]
                           [:warning warning]))})))

(defn- initialize!
  "Answers `initialize`, which opens the channel to the client. The channel
  opens even if the proxy fails to start serving, with an error as the
  answer, since what it holds would otherwise never be sent. If no root has a
  config, the error tells the client not to retry. Retrying would only find
  none again."
  [proxy {:strs [id params]}]
  (let [{:keys [result messages error]}
        (try
          (let [loaded (session/load-config (session/config-roots params (:cwd proxy)) (:config-file proxy))]
            (if (:repo loaded)
              (start-session! proxy params loaded)
              {:error [lsp/request-failed (second (:problem loaded)) {"retry" false}]}))
          (catch Exception e
            {:error [lsp/internal-error (ex-message e)]}))]
    (when-let [[_ message] error]
      (log/info "couldn't initialize -" message))
    (channel/open! (:channel proxy) (if error
                                      (apply lsp/error-response id error)
                                      (lsp/response id result)))
    (when-not error
      (register! proxy)
      (doseq [[type message] messages]
        (show-message! proxy type message)))))

;; Backends

(defn- document-selector
  "The backend's files: those of its languages in its scope, in the
  directories it also serves or among its fetched files, and those matching
  its globs."
  [b]
  (let [{:keys! [languages globs repo dir also cache]} (:spec b)
        relative (fn [base pattern] {"baseUri" (paths/path->uri base) "pattern" pattern})]
    (vec (concat (for [language languages
                       base (concat [dir] also [cache])]
                   {"language" language "pattern" (relative base "**/*")})
                 (for [glob globs]
                   {"pattern" (relative repo glob)})))))

(defn- live-backends
  "Every live backend, which is one that is starting or ready, in priority
  order."
  [proxy]
  (let [{:keys [session registry]} @(:state proxy)]
    (routing/prioritised (:config session) (:backends registry))))

(defn- merged? [registration]
  (= merged-method (get registration "method")))

(defn- server-capabilities
  "The server capabilities of the backend `b`, with the commands it registered
  itself added to those it announced, all qualified with its server type."
  [proxy b]
  (let [registered (for [r (vals (get-in @(:state proxy) [:client :registered-by (:backend-id b)]))
                         :when (merged? r)
                         command (get-in r ["registerOptions" "commands"])]
                     command)]
    (cond-> (origin/qualify-command-ids (backend/server-type b) (backend/capabilities b) ["executeCommandProvider" "commands"])
      (seq registered) (update-in ["executeCommandProvider" "commands"] #(vec (distinct (concat % registered)))))))

(defn- ready-backends
  "The server capabilities and document selector of each live backend that
  has started, in priority order."
  [proxy]
  (for [b (live-backends proxy)
        :when (backend/ready? b)]
    [(server-capabilities proxy b) (document-selector b)]))

(defn- offering
  "The ready backends that offer `command`, which is qualified with its server
  type."
  [proxy command]
  (for [b (live-backends proxy)
        :when (and (backend/ready? b)
                   (some #{command} (get-in (server-capabilities proxy b) ["executeCommandProvider" "commands"])))]
    b))

(defn- ask-client!
  "Sends the client a request about registrations, and logs it if the client
  refuses."
  [proxy method params]
  (request-client! proxy method params nil
                   (fn [{:strs [error]}]
                     (when error
                       (log/info "the client refused" method "-" (get error "message"))))))

(defn- unannounced
  "The registration `r` without what the answer to `initialize` announced, or
  nil if that was all of it. Commands can be announced in part, since a
  backend can offer more of them later."
  [{:keys [announced announced-commands]} r]
  (let [commands (vec (remove announced-commands (get-in r ["registerOptions" "commands"])))]
    (cond
      (not (contains? announced (get r "method"))) r
      (and (merged? r) (seq commands)) (assoc-in r ["registerOptions" "commands"] commands))))

(defn- register!
  "Registers each feature that any ready backend offers, with one registration
  covering all of their files. Some clients, such as VS Code, ask every
  registration that matches a file. A registration per backend would then
  bring the proxy each request several times. When a registration has to
  change, the proxy unregisters it and registers it again. When no ready
  backend offers the feature any more, the proxy unregisters it. Features
  announced in the answer to `initialize` aren't registered, apart from
  commands it didn't list. Nothing is registered until that answer has been
  sent."
  [proxy]
  (channel/serially (:channel proxy)
    (fn []
      (let [{:keys [session client]} @(:state proxy)
            {:keys [registered]} client
            wanted (when-not (channel/held? (:channel proxy))
                     (keep #(unannounced client %)
                           (capabilities/registrations (get-in session [:client-params "capabilities"])
                                                       (ready-backends proxy))))
            wanted-by-method (into {} (map (juxt #(get % "method") identity)) wanted)
            withdrawn (remove #(contains? wanted-by-method (get % "method")) (vals registered))
            changed (remove #(= % (get registered (get % "method"))) wanted)
            replaced (filter #(contains? registered (get % "method")) changed)
            unregistered (concat withdrawn replaced)]
        (swap! (:state proxy) assoc-in [:client :registered] wanted-by-method)
        (when (seq unregistered)
          (ask-client! proxy "client/unregisterCapability"
                       {"unregisterations" (mapv #(select-keys % ["id" "method"]) unregistered)}))
        (when (seq changed)
          (ask-client! proxy "client/registerCapability" {"registrations" (vec changed)}))))))

(defn- init-params [b client-params]
  (let [{:keys! [root dir]} (:spec b)
        root-uri (paths/path->uri root)]
    (into {}
          (remove (comp nil? val))
          {"processId" (.pid (ProcessHandle/current))
           "clientInfo" (get client-params "clientInfo")
           "locale" (get client-params "locale")
           "rootUri" root-uri
           "rootPath" root
           "workspaceFolders" [{"uri" root-uri
                                "name" (last (str/split dir #"/"))}]
           "capabilities" (assoc-in (get client-params "capabilities" {})
                                    ["general" "positionEncodings"] ["utf-16"])
           "trace" (get client-params "trace")})))

(defn- report!
  "Shows the user a message about the backend `b`, if one is due for its slot
  (see `registry/report-due?`). Otherwise, it only logs the message."
  [proxy b type message]
  (let [k (backend/slot b)
        now (now-ms proxy)
        [{old :registry}] (swap-vals! (:state proxy) update :registry registry/reported k now)]
    (if (registry/report-due? old k now)
      (show-message! proxy type message)
      (log/info message))))

(declare from-backend! failed! open-documents-in!)

(defn- start-backend! [proxy [server-type scope :as k]]
  (let [{:keys [specs client-params]} (:session @(:state proxy))
        timeouts (into {} (filter val) (select-keys proxy (keys backend/default-timeouts)))
        b (backend/backend (get specs k) timeouts)]
    (log/info "starting" server-type "for" (pr-str scope) "as" (:backend-id b))
    (swap! (:state proxy) update :registry registry/started b)
    (backend/start! b (:connect proxy) (init-params b client-params) #(from-backend! proxy b %))
    (open-documents-in! proxy b)
    (backend/when-settled b (fn [ready?]
                              (if ready?
                                (register! proxy)
                                (do (failed! proxy b :error (str "failed to start: " (backend/error b)))
                                    (in-background (str "stop " (:backend-id b))
                                                   #(backend/exit! b exit-timeout-ms))))))
    b))

(defn- backend-for!
  "The backend for `k`, a [server-type scope], started if it isn't live.
  Returns nil if it can't start yet (see `registry/may-start?`)."
  [proxy k]
  (let [{:keys [backends] :as registry} (:registry @(:state proxy))]
    (cond
      (contains? backends k) (get-in backends [k :backend])
      (registry/may-start? registry k (now-ms proxy)) (start-backend! proxy k))))

(defn- publish-diagnostics!
  "Replaces the backend `b`'s diagnostics for the file at `uri`, and publishes
  every backend's diagnostics for it together. Since each publication replaces
  what the client had for the file, forwarding one backend's own would hide
  the others'."
  [proxy b {:strs [uri version] :as params}]
  (channel/serially (:channel proxy)
    (fn []
      (let [state (swap! (:state proxy) update-in [:client :diagnostics]
                         diagnostics/published uri (:backend-id b) (get params "diagnostics"))]
        (send-client! proxy (lsp/notification "textDocument/publishDiagnostics"
                                              (cond-> {"uri" uri
                                                       "diagnostics" (diagnostics/union-for (get-in state [:client :diagnostics]) uri)}
                                                version (assoc "version" version))))))))

(defn- clear-diagnostics!
  "Withdraws the diagnostics of the backend `b`, which has stopped."
  [proxy b]
  (doseq [uri (diagnostics/files-from (get-in @(:state proxy) [:client :diagnostics]) (:backend-id b))]
    (publish-diagnostics! proxy b {"uri" uri "diagnostics" []})))

(defn- withdraw-registrations!
  "Unregisters what the backend `b`, which has stopped, registered itself and
  hasn't unregistered. That leaves out its commands, which the client only
  knows through the proxy's own registration. `register!` replaces that."
  [proxy b]
  (let [[old] (swap-vals! (:state proxy) update-in [:client :registered-by] dissoc (:backend-id b))
        registrations (remove merged? (vals (get-in old [:client :registered-by (:backend-id b)])))]
    (when (seq registrations)
      (ask-client! proxy "client/unregisterCapability"
                   {"unregisterations" (mapv #(select-keys % ["id" "method"]) registrations)}))))

(defn- failed!
  "Replaces the backend `b`, which failed to start or stopped, with a record of
  why and when under :failures, withdraws its diagnostics and registrations,
  and tells the user. `what-happened` follows the backend's description in the
  message."
  [proxy b type what-happened]
  (let [message (registry/failure-message b what-happened)
        [old] (swap-vals! (:state proxy) update :registry registry/failed b message (now-ms proxy))]
    (when (registry/entry (:registry old) b)
      (clear-diagnostics! proxy b)
      (when (= :serving (:phase old))
        (withdraw-registrations! proxy b)
        (register! proxy)
        (report! proxy b type message)))))

(defn- stopped!
  "Handles the backend `b` stopping. A backend that stops while starting has
  failed to start instead, which is handled once it settles."
  [proxy b]
  (log/info (:backend-id b) "stopped")
  (when (= :exited (backend/status b))
    (failed! proxy b :warning (str "stopped" (some->> (backend/exit-code b backend/exit-code-wait-ms) (str " with exit code "))))))

(defn- qualify
  "An ID that the backend `b` chose for the client, made unique among all
  backends. Two backends of one server type will often choose the same IDs."
  [b id]
  (str (:backend-id b) "/" id))

(defn- qualify-ids [b registrations]
  (mapv #(update % "id" (partial qualify b)) registrations))

(defn- qualify-registered-commands [b registrations]
  (mapv #(origin/qualify-command-ids (backend/server-type b) % ["registerOptions" "commands"]) registrations))

(defn- progress-token!
  "The token to give the client for one that the backend `b` created. The
  client's `window/workDoneProgress/cancel` finds the backend by it."
  [proxy b token]
  (let [qualified (qualify b token)]
    (swap! (:state proxy) assoc-in [:client :progress qualified] {:backend b :token token})
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
  is qualified. A token the client chose is left as it is."
  [proxy b {:strs [token value] :as params}]
  (let [qualified (qualify b token)]
    (if (contains? (get-in @(:state proxy) [:client :progress]) qualified)
      (do (when (= "end" (get value "kind"))
            (swap! (:state proxy) update-in [:client :progress] dissoc qualified))
          (assoc params "token" qualified))
      params)))

(defn- limit-selectors
  "The params of `client/registerCapability` from the backend `b`, as client
  paths, with each document selector limited to the backend's files."
  [b params]
  (let [selector (document-selector b)]
    (update params "registrations"
            (fn [registrations]
              (mapv #(cond-> %
                       (contains? (get % "registerOptions") "documentSelector")
                       (update-in ["registerOptions" "documentSelector"]
                                  capabilities/limit-selector selector))
                    registrations)))))

(defn- note-registrations!
  "Notes what the backend `b` registers or unregisters itself, from the
  `params` of its request `method` with their IDs qualified."
  [proxy b method params]
  (let [path [:client :registered-by (:backend-id b)]]
    (case method
      "client/registerCapability"
      (swap! (:state proxy) update-in path (fnil into {}) (map (juxt #(get % "id") identity)) (get params "registrations"))

      "client/unregisterCapability"
      (swap! (:state proxy) update-in path #(apply dissoc % (map (fn [u] (get u "id")) (get params "unregisterations"))))

      nil)))

(defn- passed-on
  "The `params` of the backend's request `method` for the client, without the
  registrations that `register!` merges into the proxy's own. Returns nil if
  there were only those."
  [method params]
  (if-let [k (get registration-lists method)]
    (let [passed (vec (remove merged? (get params k)))]
      (when (seq passed)
        (assoc params k passed)))
    params))

(defn- from-backend! [proxy b {:strs [id method params] :as msg}]
  (cond
    (nil? msg)
    (stopped! proxy b)

    (contains? msg "id")
    (let [params (cond->> (fetched/to-client! b (request-params proxy b method params))
                   (= "client/registerCapability" method) (limit-selectors b))
          passed (passed-on method params)]
      (note-registrations! proxy b method params)
      (when-not (= passed params)
        (register! proxy))
      (if passed
        (request-client! proxy method passed
                         [(:backend-id b) id]
                         (fn [response]
                           (backend/send! b (assoc (translate/to-server (:spec b) (dissoc response "id")) "id" id))))
        (backend/send! b (lsp/response id nil))))

    (= "$/cancelRequest" method)
    (cancel-client-request! proxy [(:backend-id b) (get params "id")])

    (= "$/progress" method)
    (send-client! proxy (lsp/notification method (fetched/to-client! b (progress-params proxy b params))))

    (= "textDocument/publishDiagnostics" method)
    (publish-diagnostics! proxy b (fetched/to-client! b params))

    :else
    (send-client! proxy (lsp/notification method (fetched/to-client! b params)))))

;; Documents

(defn- handlers [proxy uri language]
  (let [{:keys [session registry]} @(:state proxy)]
    (routing/handlers session (:backends registry) uri language)))

(defn- opened-in
  "The live backends that have been sent `didOpen` for the file at `uri`,
  in priority order."
  [proxy uri]
  (let [registry (:registry @(:state proxy))]
    (filter #(registry/opened? registry % uri) (live-backends proxy))))

(defn- live-handlers
  "The live backends that handle the file at `uri`. While the client has
  the file open, those are the ones that have been sent `didOpen` for it. A
  backend that started after the file opened knows nothing of it."
  [proxy uri]
  (let [{:keys [client registry]} @(:state proxy)]
    (if (contains? (:documents client) uri)
      (opened-in proxy uri)
      (keep #(get-in registry [:backends % :backend]) (handlers proxy uri nil)))))

(defn- notify-backend! [b method params]
  (let [params (translate/to-server (:spec b) params)]
    (backend/when-settled b (fn [ready?]
                              (when ready?
                                (backend/notify! b method params))))))

(defn- open-in!
  "Sends the backend `b` the `didOpen` with `params`, unless it has been sent
  one for the file already or has stopped."
  [proxy b params]
  (let [uri (get-in params ["textDocument" "uri"])
        [{old :registry}] (swap-vals! (:state proxy) update :registry registry/opened b uri)]
    (when (and (registry/entry old b) (not (registry/opened? old b uri)))
      (notify-backend! b "textDocument/didOpen" params))))

(defn- open-documents-in!
  "Sends the backend `b` a `didOpen` for each document the client has open
  that it handles, as the client last changed it. The backend gets these
  before anything sent to it later."
  [proxy b]
  (let [k (backend/slot b)]
    (doseq [[uri {:keys [language] :as doc}] (get-in @(:state proxy) [:client :documents])
            :when (some #{k} (handlers proxy uri language))]
      (open-in! proxy b (documents/open-params uri doc)))))

(defn- open! [proxy {:strs [textDocument] :as params}]
  (let [{:strs [uri languageId]} textDocument]
    (swap! (:state proxy) update-in [:client :documents] documents/opened params)
    (doseq [k (handlers proxy uri languageId)
            :let [b (backend-for! proxy k)]
            :when b]
      (open-in! proxy b params))))

(defn- close [state uri]
  (-> state
      (update-in [:client :documents] dissoc uri)
      (update :registry registry/closed uri)))

(defn- change-in!
  "Sends the backend `b` the client's `didChange` with `params`, as the
  backend's sync kind asks for it, given the document's `text` before the
  change. A backend that wants no changes gets nothing."
  [b params text]
  (backend/when-settled b (fn [ready?]
                            (when-let [changes (and ready?
                                                    (documents/content-changes
                                                     (capabilities/sync-kind (backend/capabilities b))
                                                     text
                                                     (get params "contentChanges")))]
                              (->> (assoc params "contentChanges" changes)
                                   (translate/to-server (:spec b))
                                   (backend/notify! b "textDocument/didChange"))))))

(defn- document-notification! [proxy method params]
  (let [uri (get-in params ["textDocument" "uri"])
        text (get-in @(:state proxy) [:client :documents uri :text])]
    (doseq [b (opened-in proxy uri)]
      (if (= "textDocument/didChange" method)
        (change-in! b params text)
        (notify-backend! b method params)))
    (case method
      "textDocument/didChange" (swap! (:state proxy) update-in [:client :documents] documents/changed params)
      "textDocument/didClose" (swap! (:state proxy) close uri)
      nil)))

;; The workspace

(defn- notify-all! [proxy method params]
  (doseq [b (live-backends proxy)]
    (notify-backend! b method params)))

(defn- watched-files-changed!
  "Passes on each change to the backends whose scopes, or the directories they
  also serve, contain the file."
  [proxy {:strs [changes]}]
  (let [within? (fn [b change]
                  (let [{:keys! [dir also]} (:spec b)
                        path (paths/uri->path (get change "uri"))]
                    (some #(paths/under? % path) (cons dir also))))]
    (doseq [b (live-backends proxy)
            :let [theirs (filterv #(within? b %) changes)]
            :when (seq theirs)]
      (notify-backend! b "workspace/didChangeWatchedFiles" {"changes" theirs}))))

;; Requests

(defn- result-for-client
  "The result of the request `method` from the backend `b`, ready for the
  client."
  [b method result]
  (let [result (cond->> (fetched/to-client! b (normalise/result method result))
                 (get-in methods/table [method :commands?]) (origin/qualify-commands (backend/server-type b)))]
    (origin/mark-result method (:backend-id b) result)))

(defn- forward-request! [proxy b {:strs [id method params]}]
  (backend/request! b method (translate/to-server (:spec b) params) id
                    (fn [response]
                      (send-client! proxy
                                    (cond
                                      (nil? response)
                                      (lsp/error-response id lsp/internal-error (str (backend/server-type b) " stopped before answering"))

                                      (contains? response "error")
                                      (assoc (fetched/to-client! b response) "id" id)

                                      :else
                                      (lsp/response id (result-for-client b method (get response "result"))))))))

(defn- failed-response
  "An error answering the request `id`, which an agent sees in its tool's
  result, with why the backends that would have answered it failed."
  [id reasons]
  (lsp/error-response id lsp/request-failed (str/join "\n" reasons)))

(defn- answer-unanswered!
  "Answers a request that no live backend can, with an empty result. If no
  backend that would handle the request's file is live, and some have
  failed, the answer is an error saying why instead."
  [proxy {:strs [id method params]}]
  (let [{:keys [client registry]} @(:state proxy)
        uri (get-in params ["textDocument" "uri"])
        ks (handlers proxy uri (get-in client [:documents uri :language]))
        reasons (when (not-any? #(contains? (:backends registry) %) ks)
                  (registry/failures registry ks))]
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
    (backend/when-settled b
      (fn [ready?]
        (let [feature (capabilities/feature method)]
          (if (and ready?
                   (or (nil? feature) (capabilities/offers? (backend/capabilities b) feature)))
            (forward-request! proxy b msg)
            (route-request! proxy msg more)))))))

(defn- when-all-settled
  "Calls `f` once every one of `backends` has started or failed to start, with
  those that started, in the same order."
  ([backends f] (when-all-settled backends [] f))
  ([backends ready f]
   (if-let [[b & more] (seq backends)]
     (backend/when-settled b (fn [ready?]
                               (when-all-settled more (cond-> ready ready? (conj b)) f)))
     (f ready))))

(defn- ask-each!
  "Sends the request to each of `backends` that offers its feature, once that
  backend has settled, in order with what else is sent to it. Calls
  `on-answers` with a [backend response] pair for each backend asked, in the
  same order, once they have all answered. A backend that stops first answers
  nil."
  [backends {:strs [id method params]} on-answers]
  (let [n (count backends)
        feature (capabilities/feature method)
        answers (atom {})
        answer! (fn [i answer]
                  (let [all (swap! answers assoc i answer)]
                    (when (= n (count all))
                      (on-answers (keep all (range n))))))]
    (when (zero? n)
      (on-answers []))
    (doseq [[i b] (map-indexed vector backends)]
      (backend/when-settled b
        (fn [ready?]
          (if (and ready? (capabilities/offers? (backend/capabilities b) feature))
            (backend/request! b method (translate/to-server (:spec b) params) id #(answer! i [b %]))
            (answer! i nil)))))))

(defn- answered? [response]
  (and response (not (contains? response "error"))))

(defn- combined-response
  "The response to the client's request `id` from the backends' `responses`.
  Backends that failed to answer are left out, unless they all did."
  [id method backends responses]
  (let [answers (for [[b response] (map vector backends responses)
                      :when (answered? response)]
                  [(backend/server-type b) (result-for-client b method (get response "result"))])]
    (doseq [[b response] (map vector backends responses)
            :when (not (answered? response))]
      (log/info (:backend-id b) "didn't answer" method "-"
                (get-in response ["error" "message"] "it stopped")))
    (cond
      (seq answers) (lsp/response id (merge/combine method answers))
      (some some? responses) (assoc (first (filter some? responses)) "id" id)
      :else (lsp/error-response id lsp/internal-error "every backend stopped before answering"))))

(defn- combine-request!
  "Sends a request to every one of `backends` that is ready and offers the
  request's feature, and answers with their combined results. If none is, it
  asks each of `backends` that offers the feature as that backend settles,
  then answers once all of them have."
  [proxy {:strs [id method] :as msg} backends]
  (let [feature (capabilities/feature method)
        answering (filter #(and (backend/ready? %) (capabilities/offers? (backend/capabilities %) feature))
                          backends)]
    (ask-each! (if (seq answering) answering backends) msg
               (fn [answers]
                 (if (empty? answers)
                   (answer-unanswered! proxy msg)
                   (send-client! proxy (combined-response id method (map first answers) (map second answers))))))))

(defn- backend-by-id [proxy backend-id]
  (first (filter #(= backend-id (:backend-id %)) (live-backends proxy))))

(defn- item-maker
  "The backend that made the marked `item`, and the item as that backend made
  it. Nil if the item isn't marked or the backend isn't ready."
  [proxy item]
  (let [[backend-id item] (origin/unmark item)
        b (some->> backend-id (backend-by-id proxy))]
    (when (and b (backend/ready? b))
      [b item])))

(defn- resolve!
  "Sends a request to resolve an item to the backend that made it. The item
  comes back unchanged if that backend has stopped or doesn't resolve items."
  [proxy {:strs [id method params] :as msg}]
  (let [[b item] (item-maker proxy params)]
    (if (and b (capabilities/resolves? (backend/capabilities b) method))
      (forward-request! proxy b (assoc msg "params" item))
      (send-client! proxy (lsp/response id params)))))

(defn- follow-up!
  "Sends a request about an item from an earlier answer, such as the incoming
  calls of a call hierarchy item, to the backend that made the item. The
  answer is null if that backend has stopped or doesn't offer the feature."
  [proxy {:strs [id method params] :as msg}]
  (let [[b item] (item-maker proxy (get params "item"))]
    (if (and b (capabilities/offers? (backend/capabilities b) (capabilities/feature method)))
      (forward-request! proxy b (assoc-in msg ["params" "item"] item))
      (send-client! proxy (lsp/response id nil)))))

(defn- none-ready-response
  "The answer to the request `id` for a command of `server-type`, which has no
  ready backend to run it."
  [proxy id server-type]
  (let [registry (:registry @(:state proxy))
        reasons (registry/failures registry (sort (filter #(= server-type (first %)) (keys (:failures registry)))))]
    (if (seq reasons)
      (failed-response id reasons)
      (lsp/error-response id lsp/internal-error (str "no " server-type " backend is running")))))

(defn- execute-command!
  "Sends `workspace/executeCommand` to a backend of the server type that the
  command is qualified with, as the command ID the backend chose. If none is
  ready, the answer is an error saying why."
  [proxy {:strs [id params] :as msg}]
  (let [server-types (keys (get-in @(:state proxy) [:session :config "servers"]))
        [server-type command] (origin/command-origin server-types (get params "command"))
        b (when server-type
            (routing/command-backend (get-in @(:state proxy) [:session :repo]) (live-backends proxy)
                                     server-type (offering proxy (get params "command")) (get params "arguments")))
        none-ready #(send-client! proxy (none-ready-response proxy id server-type))]
    (cond
      (nil? server-type)
      (send-client! proxy (lsp/error-response id lsp/invalid-params (str "unknown command " (pr-str (get params "command")))))

      (nil? b)
      (none-ready)

      :else
      (backend/when-settled b (fn [ready?]
                                (if ready?
                                  (forward-request! proxy b (assoc-in msg ["params" "command"] command))
                                  (none-ready)))))))

(defn- cancel!
  "Passes on the client's cancellation of a request it sent a backend."
  [proxy client-id]
  (doseq [b (live-backends proxy)
          :let [id (backend/tagged-request b client-id)]
          :when id]
    (backend/notify! b "$/cancelRequest" {"id" id})))

(defn- cancel-progress!
  "Passes on the client's cancellation of progress a backend created."
  [proxy token]
  (when-let [{:keys [backend token]} (get-in @(:state proxy) [:client :progress token])]
    (backend/notify! backend "window/workDoneProgress/cancel" {"token" token})))

(defn- shutdown!
  "Answers `shutdown` once every backend has, or once they've had
  `shutdown-timeout-ms` in all. The client's thread doesn't wait, since a
  backend may need answers from the client before it can shut down."
  [proxy id]
  (swap! (:state proxy) assoc :phase :shutting-down)
  (let [backends (live-backends proxy)
        answers (mapv backend/shutdown! backends)
        deadline (+ (now-ms proxy) shutdown-timeout-ms)]
    (in-background "answer shutdown"
      (fn []
        (doseq [[b answered] (map vector backends answers)
                :let [ms-left (max 0 (- deadline (now-ms proxy)))]]
          (when (= ::timeout (deref answered ms-left ::timeout))
            (log/info (:backend-id b) "didn't answer shutdown in time")))
        (send-client! proxy (lsp/response id nil))))))

(defn- exit! [proxy]
  (let [[old] (swap-vals! (:state proxy) assoc :phase :exiting)]
    (run! deref (mapv (fn [b] (in-background (str "stop " (:backend-id b))
                                             #(backend/exit! b exit-timeout-ms)))
                      (live-backends proxy)))
    ((:exit proxy) (if (= :shutting-down (:phase old)) 0 1))))

;; The client's messages

(defn- uninitialized? [proxy]
  (= :uninitialized (:phase @(:state proxy))))

(defn- client-request! [proxy {:strs [id method params] :as msg}]
  (let [uri (get-in params ["textDocument" "uri"])]
    (cond
      (= "initialize" method) (initialize! proxy msg)
      (uninitialized? proxy) (send-client! proxy (lsp/error-response id lsp/server-not-initialized "not initialized"))
      (= "shutdown" method) (shutdown! proxy id)
      (capabilities/resolve-method? method) (resolve! proxy msg)
      (capabilities/follow-up-method? method) (follow-up! proxy msg)
      (= "workspace/executeCommand" method) (execute-command! proxy msg)
      (= "workspace/symbol" method) (combine-request! proxy msg (live-backends proxy))
      (and uri (capabilities/combined? method)) (combine-request! proxy msg (live-handlers proxy uri))
      uri (route-request! proxy msg (live-handlers proxy uri))
      :else (send-client! proxy (lsp/response id nil)))))

(defn- client-notification! [proxy {:strs [method params]}]
  (cond
    (= "exit" method) (exit! proxy)
    (uninitialized? proxy) nil
    :else (case method
            "textDocument/didOpen" (open! proxy params)
            ("textDocument/didChange" "textDocument/didSave" "textDocument/didClose")
            (document-notification! proxy method params)
            "workspace/didChangeConfiguration" (notify-all! proxy method params)
            "workspace/didChangeWatchedFiles" (watched-files-changed! proxy params)
            "$/cancelRequest" (cancel! proxy (get params "id"))
            "window/workDoneProgress/cancel" (cancel-progress! proxy (get params "token"))
            nil)))

(defn- without-partial-results
  "The client's request `msg` without its token for partial results. A backend
  would send those straight to the client, past the marking, qualifying and
  fetching that the proxy does to every answer. Without the token, backends
  answer in full instead."
  [msg]
  (cond-> msg
    (map? (get msg "params")) (update "params" dissoc "partialResultToken")))

(defn handle-client!
  "Handles one message from the client. If handling a request fails, the
  answer is an error saying why."
  [proxy msg]
  (try
    (cond
      (lsp/response? msg) (client-response! proxy msg)
      (contains? msg "id") (client-request! proxy (without-partial-results msg))
      :else (client-notification! proxy msg))
    (catch Exception e
      (log/info "couldn't handle" (get msg "method" "a response") "-" (ex-message e))
      (when (and (contains? msg "id") (contains? msg "method"))
        (send-client! proxy (lsp/error-response (get msg "id") lsp/internal-error (ex-message e)))))))
