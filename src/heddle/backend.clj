(ns heddle.backend
  "Backends: the language servers the proxy starts, one for each server type in
  each scope. See \"Terms\" in DESIGN.md.

  The proxy talks to a backend over a connection, which is a map of functions.
  :send takes a message, :close ends the backend's input, :kill stops it
  however it's stuck, and :wait takes a timeout in milliseconds and returns the
  backend's exit code, or nil if it's still running. A connector makes the
  connection, given the backend and a function to call with each message the
  backend sends, then with nil once it stops. `connect-process` is the real
  connector. Tests use fake backends in the same process instead."
  (:require [clojure.java.io :as io]
            [heddle.log :as log]
            [heddle.lsp :as lsp]
            [heddle.process :as process]
            [heddle.requests :as requests]
            [heddle.spec :as spec])
  (:import (java.io BufferedInputStream InputStream)
           (java.lang ProcessBuilder$Redirect)
           (java.util UUID)
           (java.util.concurrent TimeUnit)))

(def default-timeouts
  {:start-timeout-ms 120000 ; for `prepare` and the answer to `initialize`, together
   :read-timeout-ms 10000}) ; for each `read` of a fetched file

(def exit-code-wait-ms 1000) ; for a backend that has stopped to give its exit code
(def ^:private slow-prepare-ms 1000) ; long enough to mention when initialize times out

(defn new-id
  "A backend ID, which is the project's name and a random suffix. It only uses
  characters that Docker allows in a container name."
  []
  (str "heddle-" (subs (str (UUID/randomUUID)) 0 8)))

(defn- read-loop [^InputStream in on-message]
  (try
    (loop []
      (when-let [msg (lsp/read-message in)]
        (on-message msg)
        (recur)))
    (catch Exception e
      (log/info "reading from a backend:" (ex-message e)))
    (finally
      (on-message nil))))

(defn spawn
  "Starts `command` in the directory `dir`, with stderr inherited, and returns a
  connection to it."
  [command dir on-message]
  (let [proc (.start (doto (ProcessBuilder. ^java.util.List command)
                       (.directory (io/file dir))
                       (.redirectError ProcessBuilder$Redirect/INHERIT)))
        out (.getOutputStream proc)]
    (future (read-loop (BufferedInputStream. (.getInputStream proc)) on-message))
    {:send #(lsp/write-message out %)
     :close #(.close out)
     :kill #(process/kill! proc)
     :wait (fn [ms]
             (when (.waitFor proc ms TimeUnit/MILLISECONDS)
               (.exitValue proc)))}))

(defn- prepare!
  "Runs `command` in `dir` to completion, killing it after `timeout-ms`. Its
  output goes to stderr, since stdout is the client's LSP stream."
  [command dir timeout-ms]
  (let [{:keys [exit timed-out output]} (process/run command dir {:timeout-ms timeout-ms})]
    (binding [*out* *err*]
      (print output)
      (flush))
    (cond
      timed-out
      (throw (ex-info (str "its prepare command " (pr-str command) " didn't finish within "
                           (process/duration timeout-ms))
                      {:command command}))

      (not (zero? exit))
      (throw (ex-info (str "its prepare command " (pr-str command) " exited with " exit)
                      {:command command :exit exit})))))

(defn connect-process
  "Connects to the backend by running its runner's `prepare` command, if it has
  one, then its `start` command. Both run in the scope's directory. Since
  connecting is the first part of starting, `prepare` has the whole of the
  backend's :start-timeout-ms."
  [{:keys! [spec backend-id start-timeout-ms]} on-message]
  (let [{:keys! [dir]} spec
        runtime {:backend-id backend-id}]
    (when-let [command (spec/command spec "prepare" runtime)]
      (prepare! command dir start-timeout-ms))
    (spawn (spec/command spec "start" runtime) dir on-message)))

(defn backend
  "A backend for the `spec` (see heddle.spec), not yet started. `timeouts`
  overrides any of `default-timeouts`."
  ([spec] (backend spec {}))
  ([spec timeouts]
   (merge default-timeouts
          timeouts
          {:spec spec
           :backend-id (new-id)
           :refreshed (atom #{})
           ;; :stopped once the connection has closed, whatever the :status
           :state (atom {:status :starting
                         :queue []
                         :requests requests/empty-table
                         :stopped false})})))

(defn status
  "One of :starting, :ready, :failed or :exited."
  [backend]
  (:status @(:state backend)))

(defn ready? [backend]
  (= :ready (status backend)))

(defn capabilities
  "The server capabilities the backend answered `initialize` with."
  [backend]
  (:capabilities @(:state backend)))

(defn error
  "Why the backend failed to start, if it did."
  [backend]
  (:error @(:state backend)))

(defn exit-code
  "The backend's exit code, waiting up to `ms` for it to exit, or nil if it
  hasn't or was never connected."
  [backend ms]
  (when-let [{:keys [wait]} (:conn @(:state backend))]
    (wait ms)))

(defn send! [backend msg]
  (if-let [conn (:conn @(:state backend))]
    (try
      ((:send conn) msg)
      (catch Exception e
        (log/info (:backend-id backend) "couldn't be sent a message:" (ex-message e))))
    (log/info (:backend-id backend) "isn't connected; dropped" (get msg "method" "a response"))))

(defn notify! [backend method params]
  (send! backend (lsp/notification method params)))

(defn request!
  "Sends a request and calls `on-response` with the backend's response, or with
  nil if the backend stops first. Returns the request's ID. `tag` identifies
  the request for `tagged-request` while it's open."
  ([backend method params on-response]
   (request! backend method params nil on-response))
  ([backend method params tag on-response]
   (let [{:keys [stopped] {:keys [last-id]} :requests}
         (swap! (:state backend)
                #(cond-> % (not (:stopped %)) (update :requests requests/add tag on-response)))]
     (if stopped
       (on-response nil)
       (send! backend (lsp/request last-id method params)))
     last-id)))

(defn tagged-request
  "The ID of the open request tagged with `tag`, or nil if there isn't one."
  [backend tag]
  (first (requests/tagged (:requests @(:state backend)) tag)))

(defn- take-callback! [backend id]
  (let [[old] (swap-vals! (:state backend) update :requests requests/answered id)]
    (requests/on-response (:requests old) id)))

(defn- stopped! [backend]
  (let [[old] (swap-vals! (:state backend)
                          #(cond-> (-> % (assoc :stopped true) (assoc-in [:requests :open] {}))
                             (= :ready (:status %)) (assoc :status :exited)))]
    (doseq [{:keys [on-response]} (vals (get-in old [:requests :open]))]
      (on-response nil))))

(defn- receive [backend on-message msg]
  (try
    (cond
      (nil? msg) (do (stopped! backend)
                     (on-message nil))
      (lsp/response? msg) (if-let [on-response (take-callback! backend (get msg "id"))]
                            (on-response msg)
                            (log/info (:backend-id backend) "answered an unknown request" (get msg "id")))
      :else (on-message msg))
    (catch Exception e
      (log/info (:backend-id backend) "sent a message that couldn't be handled:" (ex-message e)))))

(defn- run-queued [backend f]
  (try
    (f)
    (catch Exception e
      (log/info (:backend-id backend) "couldn't be sent a queued message:" (ex-message e)))))

(defn- queueing? [{:keys [status draining]}]
  (or (= :starting status) draining))

(defn when-settled
  "Calls `f` once the backend has either started or failed to start, after
  anything queued before it, with whether it's ready. Until a backend has
  answered `initialize`, nothing else can be sent to it."
  [backend f]
  (let [run #(f (ready? backend))
        [old] (swap-vals! (:state backend)
                          #(cond-> % (queueing? %) (update :queue conj run)))]
    (when-not (queueing? old)
      (run))))

(defn- settle!
  "Moves the backend to `status`, then runs everything queued, including
  anything queued meanwhile. The backend keeps queueing until it takes an
  empty queue. That way, nothing can run ahead of what's already queued."
  [backend status]
  (swap! (:state backend) assoc :status status :draining true)
  (loop []
    (let [[{:keys [queue]}] (swap-vals! (:state backend)
                                        #(assoc % :queue [] :draining (boolean (seq (:queue %)))))]
      (when (seq queue)
        (run! #(run-queued backend %) queue)
        (recur)))))

(defn- initialize!
  "Initializes the backend, killing it if it hasn't answered within its
  :start-timeout-ms of `started`, in milliseconds since the epoch."
  [{:keys [start-timeout-ms] :as backend} init-params started]
  (let [answer (promise)
        connect-ms (- (System/currentTimeMillis) started)]
    (request! backend "initialize" init-params #(deliver answer %))
    (let [response (deref answer (max 0 (- start-timeout-ms connect-ms)) ::timed-out)]
      (cond
        (= ::timed-out response)
        (do ((:kill (:conn @(:state backend))))
            (throw (ex-info (str "it didn't answer initialize within " (process/duration start-timeout-ms)
                                 (when (<= slow-prepare-ms connect-ms)
                                   (str ", of which prepare took " (process/duration connect-ms))))
                            {})))

        (nil? response)
        (throw (ex-info (str "it exited" (some->> (exit-code backend exit-code-wait-ms) (str " with "))
                             " before answering initialize")
                        {}))

        (contains? response "error")
        (throw (ex-info (str "initialize failed: " (get-in response ["error" "message"])) {})))
      (get-in response ["result" "capabilities"]))))

(defn start!
  "Connects to the backend with the connector `connect` and initializes it with
  `init-params`, on a thread of its own. Returns the backend straight away.
  `on-message` gets every message from the backend except responses to
  `request!`, then nil once the backend stops. The backend fails to start if
  connecting and initializing take longer than its :start-timeout-ms."
  [backend connect init-params on-message]
  (future
    (try
      (let [started (System/currentTimeMillis)
            conn (connect backend #(receive backend on-message %))]
        (swap! (:state backend) assoc :conn conn)
        (swap! (:state backend) assoc :capabilities (initialize! backend init-params started))
        (notify! backend "initialized" {})
        (settle! backend :ready))
      (catch Exception e
        (swap! (:state backend) assoc :error (ex-message e))
        (settle! backend :failed))))
  backend)

(defn shutdown!
  "Asks the backend to shut down once it has started. Returns a promise that is
  delivered once the backend has answered, or straight away if it never
  started."
  [backend]
  (let [done (promise)]
    (when-settled backend
      (fn [ready?]
        (if ready?
          (request! backend "shutdown" nil (fn [_] (deliver done true)))
          (deliver done false))))
    done))

(defn exit!
  "Tells the backend to exit, closes its input and waits up to `ms` for it to
  stop, then kills it if it hasn't. Returns its exit code, or nil if it had to
  be killed. Since some backends exit as soon as they've answered `shutdown`,
  failing to tell one to exit isn't logged."
  [backend ms]
  (when-let [{:keys [send close kill wait]} (:conn @(:state backend))]
    (try (send (lsp/notification "exit" nil)) (catch Exception _))
    (try (close) (catch Exception _))
    (or (wait ms)
        (do (log/info (:backend-id backend) "hasn't exited after" (process/duration ms) "and is being killed")
            (try (kill) (catch Exception e (log/info "killing" (:backend-id backend) "failed:" (ex-message e))))
            nil))))
