(ns heddle.channel
  "The proxy's channel to the client. Everything the proxy sends the client
  goes through one, from the client's thread and every backend's, and the
  channel decides the order the client gets it in. While the channel is held,
  it keeps what it's sent until it opens."
  (:require [heddle.lsp :as lsp]
            [heddle.requests :as requests]))

(defn channel
  "An open channel that sends each message to the client with `send`."
  [send]
  {:send send
   :lock (Object.)
   :state (atom {:held nil :requests requests/empty-table})})

(defn hold!
  "Keeps everything sent to the channel from now until it opens."
  [{:keys [lock state]}]
  (locking lock
    (swap! state update :held #(or % []))))

(defn held? [{:keys [lock state]}]
  (locking lock
    (some? (:held @state))))

(defn send!
  "Sends `msg` to the client, or keeps it while the channel is held."
  [{:keys [send lock state]} msg]
  (locking lock
    (if (:held @state)
      (swap! state update :held conj msg)
      (send msg))))

(defn open!
  "Sends `msgs`, then everything kept while the channel was held, and stops
  holding."
  [{:keys [send lock state]} & msgs]
  (locking lock
    (let [[old] (swap-vals! state assoc :held nil)]
      (run! send (concat msgs (:held old))))))

(defn serially
  "Calls `f` with nothing else sent meanwhile, and returns what it returns.
  `f` can send through the channel itself, since the lock is reentrant.
  Callers that work out what to send from state they share use this, so that
  what one sends can't overtake what another worked out from older state."
  [{:keys [lock]} f]
  (locking lock
    (f)))

(defn request!
  "Sends the client a request with an ID of the channel's own, and calls
  `on-response` with the client's response. `tag` says what the request was
  sent for, for `tagged-requests`."
  [ch method params tag on-response]
  (let [{:keys [requests]} (swap! (:state ch) update :requests requests/add tag on-response)]
    (send! ch (lsp/request (:last-id requests) method params))))

(defn answered!
  "Calls back the request that the client's response `msg` answers. Returns
  false if no open request has its ID."
  [ch {:strs [id] :as msg}]
  (let [[old] (swap-vals! (:state ch) update :requests requests/answered id)]
    (if-let [on-response (requests/on-response (:requests old) id)]
      (do (on-response msg) true)
      false)))

(defn tagged-requests
  "The IDs of the open requests to the client tagged `tag`."
  [ch tag]
  (requests/tagged (:requests @(:state ch)) tag))
