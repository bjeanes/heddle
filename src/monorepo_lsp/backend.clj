(ns monorepo-lsp.backend
  "Backends: the language servers the proxy starts, one for each server type in
  each scope. See \"Terms\" in DESIGN.md.

  The proxy talks to a backend over a connection, which is a map of functions.
  :send takes a message, :close ends the backend's input, and :wait takes a
  timeout in milliseconds and returns the backend's exit code, or nil if it's
  still running. A connector makes the connection, given the backend and a
  function to call with each message the backend sends, then with nil once it
  stops. `connect-process` is the real connector. Tests use fake backends in
  the same process instead."
  (:require [clojure.java.io :as io]
            [monorepo-lsp.config :as config]
            [monorepo-lsp.fetched :as fetched]
            [monorepo-lsp.log :as log]
            [monorepo-lsp.lsp :as lsp]
            [monorepo-lsp.paths :as paths])
  (:import (java.io BufferedInputStream InputStream)
           (java.lang ProcessBuilder$Redirect)
           (java.util UUID)
           (java.util.concurrent TimeUnit)))

(defn new-id
  "A backend ID, which is the project's name and a random suffix. It only uses
  characters that Docker allows in a container name."
  []
  (str "monorepo-lsp-" (subs (str (UUID/randomUUID)) 0 8)))

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
     :wait (fn [ms]
             (when (.waitFor proc ms TimeUnit/MILLISECONDS)
               (.exitValue proc)))}))

(defn- prepare!
  "Runs `command` in `dir` to completion. Its output goes to stderr, since
  stdout is the client's LSP stream."
  [command dir]
  (let [proc (.start (doto (ProcessBuilder. ^java.util.List command)
                       (.directory (io/file dir))
                       (.redirectErrorStream true)))
        output (slurp (.getInputStream proc))
        exit (.waitFor proc)]
    (binding [*out* *err*]
      (print output)
      (flush))
    (when-not (zero? exit)
      (throw (ex-info (str "its prepare command " (pr-str command) " exited with " exit)
                      {:command command :exit exit})))))

(defn connect-process
  "Connects to the backend by running its runner's `prepare` command, if it has
  one, then its `start` command. Both run in the scope's directory."
  [{:keys [config server-type scope repo backend-id]} on-message]
  (let [runtime {:repo repo :backend-id backend-id}
        dir (paths/client-path repo scope)]
    (when-let [command (config/command config server-type scope "prepare" runtime)]
      (prepare! command dir))
    (spawn (config/command config server-type scope "start" runtime) dir on-message)))

(defn backend
  "A backend for `server-type` in `scope`, not yet started. `repo` is the repo
  root as a client path, and `cache-root` is where fetched files are cached.
  The backend can be used as a context for monorepo-lsp.translate, and with
  monorepo-lsp.fetched."
  [config server-type scope repo cache-root]
  (let [id (new-id)]
    {:config config
     :server-type server-type
     :scope scope
     :repo repo
     :backend-id id
     :paths (get (config/boot-config config server-type scope) "paths")
     :cache (fetched/cache-dir cache-root repo server-type scope)
     :fetched (atom #{})
     :root (config/server-scope config server-type scope repo)
     :state (atom {:status :starting :queue []})
     :requests (atom {:last-id 0 :open {}})}))

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
   (let [{:keys [last-id stopped]}
         (swap! (:requests backend)
                (fn [{:keys [last-id] :as requests}]
                  (if (:stopped requests)
                    requests
                    (-> requests
                        (assoc :last-id (inc last-id))
                        (assoc-in [:open (inc last-id)] {:tag tag :on-response on-response})))))]
     (if stopped
       (on-response nil)
       (send! backend (lsp/request last-id method params)))
     last-id)))

(defn tagged-request
  "The ID of the open request tagged with `tag`, or nil if there isn't one."
  [backend tag]
  (first (for [[id request] (:open @(:requests backend))
               :when (= tag (:tag request))]
           id)))

(defn- take-callback! [backend id]
  (let [[old] (swap-vals! (:requests backend) update :open dissoc id)]
    (get-in old [:open id :on-response])))

(defn- stopped! [backend]
  (swap! (:state backend) #(cond-> % (= :ready (:status %)) (assoc :status :exited)))
  (let [[old] (swap-vals! (:requests backend) assoc :stopped true :open {})]
    (doseq [{:keys [on-response]} (vals (:open old))]
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

(defn when-ready
  "Calls `f` once the backend has either started or failed to start, after
  anything queued before it. Until a backend has answered `initialize`, nothing
  else can be sent to it."
  [backend f]
  (let [[old] (swap-vals! (:state backend)
                          #(cond-> % (queueing? %) (update :queue conj f)))]
    (when-not (queueing? old)
      (f))))

(defn- settle!
  "Moves the backend to `status`, then runs everything queued, including
  anything queued meanwhile. The backend keeps queueing until it takes an
  empty queue, so nothing can run ahead of what's already queued."
  [backend status]
  (swap! (:state backend) assoc :status status :draining true)
  (loop []
    (let [[{:keys [queue]}] (swap-vals! (:state backend)
                                        #(assoc % :queue [] :draining (boolean (seq (:queue %)))))]
      (when (seq queue)
        (run! #(run-queued backend %) queue)
        (recur)))))

(defn- initialize! [backend init-params]
  (let [answer (promise)]
    (request! backend "initialize" init-params #(deliver answer %))
    (let [response @answer]
      (cond
        (nil? response)
        (throw (ex-info (str "it exited" (some->> (exit-code backend 1000) (str " with "))
                             " before answering initialize")
                        {}))

        (contains? response "error")
        (throw (ex-info (str "initialize failed: " (get-in response ["error" "message"])) {})))
      (get-in response ["result" "capabilities"]))))

(defn start!
  "Connects to the backend with the connector `connect` and initializes it with
  `init-params`, on a thread of its own. Returns the backend straight away.
  `on-message` gets every message from the backend except responses to
  `request!`, then nil once the backend stops."
  [backend connect init-params on-message]
  (future
    (try
      (let [conn (connect backend #(receive backend on-message %))]
        (swap! (:state backend) assoc :conn conn)
        (swap! (:state backend) assoc :capabilities (initialize! backend init-params))
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
    (when-ready backend
      #(if (ready? backend)
         (request! backend "shutdown" nil (fn [_] (deliver done true)))
         (deliver done false)))
    done))

(defn exit!
  "Tells the backend to exit, closes its input and waits up to `ms` for it to
  stop. Returns its exit code, or nil if it hasn't stopped. Since some backends
  exit as soon as they've answered `shutdown`, failing to tell one to exit
  isn't logged."
  [backend ms]
  (when-let [{:keys [send close wait]} (:conn @(:state backend))]
    (try (send (lsp/notification "exit" nil)) (catch Exception _))
    (try (close) (catch Exception _))
    (wait ms)))
