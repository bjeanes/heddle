(ns heddle.backend-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [heddle.backend :as backend]
            [heddle.fake-backend :as fake :refer [eventually]]
            [heddle.log :as log]
            [heddle.lsp :as lsp])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (str (Files/createTempDirectory "heddle-test" (into-array FileAttribute []))))

(defn- spec
  "A spec for a server type s in the repo root, whose runner has `templates`
  and runs them in `dir`."
  ([] (spec "/r" {"start" ["s"]}))
  ([dir templates]
   {:slot ["s" "."]
    :repo "/r"
    :dir dir
    :root "/src"
    :paths {"." "/src"}
    :cache "/cache"
    :templates templates
    :context {}}))

(defn- prepares-with
  "A spec for a backend in a new directory, which runs `prepare` then cat."
  [prepare]
  (spec (temp-dir) {"prepare" prepare "start" ["cat"]}))

(defn- methods-received [received]
  (mapv #(get % "method") (get @received ["s" "."])))

(deftest backend-ids
  (let [id (backend/new-id)]
    (is (re-matches #"heddle-[0-9a-f]{8}" id))
    (is (not= id (backend/new-id)))))

(deftest a-new-backend
  (let [b (backend/backend (spec))]
    (is (= (spec) (:spec b)))
    (is (= :starting (backend/status b)))))

(deftest starting
  (let [received (atom {})
        answer-initialize (promise)
        respond (fn [_ msg]
                  (when (= "initialize" (get msg "method"))
                    @answer-initialize
                    [(lsp/response (get msg "id") {"capabilities" {"hoverProvider" true}})]))
        b (backend/start! (backend/backend (spec))
                          (fake/connector received respond)
                          {"rootUri" "file:///src"}
                          (fn [_]))]
    (backend/when-ready b #(backend/notify! b "first" nil))
    (backend/when-ready b #(backend/notify! b "second" nil))
    (testing "sends only initialize until it's answered"
      (is (eventually #(= ["initialize"] (methods-received received))))
      (is (= {"rootUri" "file:///src"} (get-in @received [["s" "."] 0 "params"])))
      (is (= :starting (backend/status b))))
    (testing "then initialized and everything queued, in order"
      (deliver answer-initialize true)
      (is (eventually #(= ["initialize" "initialized" "first" "second"] (methods-received received))))
      (is (backend/ready? b))
      (is (= {"hoverProvider" true} (backend/capabilities b))))
    (testing "sends straight away once it has started"
      (backend/when-ready b #(backend/notify! b "third" nil))
      (is (eventually #(= "third" (peek (methods-received received))))))))

(deftest failing-to-start
  (let [received (atom {})
        respond (fn [_ msg]
                  [(lsp/error-response (get msg "id") -32603 "no Gemfile")])
        b (backend/backend (spec))
        ran (promise)]
    (backend/when-ready b #(deliver ran (backend/status b)))
    (backend/start! b (fake/connector received respond) {} (fn [_]))
    (is (= :failed (deref ran 2000 :timeout)) "queued functions still run")
    (is (= "initialize failed: no Gemfile" (:error @(:state b))))
    (is (= ["initialize"] (methods-received received)))))

(deftest failing-to-answer-initialize-in-time
  (testing "kills it"
    (let [stopped (promise)
          b (backend/backend (spec) {:start-timeout-ms 100})
          ran (promise)]
      (backend/when-ready b #(deliver ran (backend/status b)))
      (backend/start! b (fake/connector (atom {}) (constantly nil)) {} #(when (nil? %) (deliver stopped true)))
      (is (= :failed (deref ran 2000 :timeout)))
      (is (= "it didn't answer initialize within 100 ms" (backend/error b)))
      (is (= true (deref stopped 2000 :timeout)))))
  (testing "says how much of the time prepare took"
    (let [b (backend/backend (prepares-with ["sleep" "1"]) {:start-timeout-ms 1200})
          ran (promise)]
      (backend/when-ready b #(deliver ran (backend/status b)))
      (backend/start! b backend/connect-process {} (fn [_]))
      (is (= :failed (deref ran 5000 :timeout)))
      (is (re-matches #"it didn't answer initialize within 1200 ms, of which prepare took 1\d\d\d ms"
                      (backend/error b))))))

(deftest failing-to-prepare-in-time
  (let [b (backend/backend (prepares-with ["sleep" "30"]) {:start-timeout-ms 200})
        ran (promise)]
    (backend/when-ready b #(deliver ran (backend/status b)))
    (backend/start! b backend/connect-process {} (fn [_]))
    (is (= :failed (deref ran 5000 :timeout)) "prepare is killed rather than left to finish")
    (is (= "its prepare command [\"sleep\" \"30\"] didn't finish within 200 ms" (backend/error b)))))

(deftest requests
  (let [stopped (promise)
        respond (fn [_ msg]
                  (case (get msg "method")
                    "initialize" [(lsp/response (get msg "id") {"capabilities" {}})]
                    "echo" [(lsp/response (get msg "id") (get msg "params"))]
                    nil))
        b (backend/start! (backend/backend (spec))
                          (fake/connector (atom {}) respond)
                          {}
                          #(when (nil? %) (deliver stopped true)))]
    (is (eventually #(backend/ready? b)))
    (testing "a response goes to its request's callback"
      (let [response (promise)]
        (backend/request! b "echo" {"x" 1} #(deliver response %))
        (is (= {"x" 1} (get (deref response 2000 nil) "result")))))
    (testing "an open request can be found by its tag"
      (let [id (backend/request! b "unanswered" nil "client-7" (fn [_]))]
        (is (= id (backend/tagged-request b "client-7")))
        (is (nil? (backend/tagged-request b "client-8")))))
    (testing "once the backend stops, its open requests are answered with nil"
      (let [response (promise)]
        (backend/request! b "unanswered" nil #(deliver response %))
        (is (= 0 (backend/exit! b 1000)))
        (is (= true (deref stopped 2000 :timeout)))
        (is (nil? (deref response 2000 :timeout)))
        (is (= :exited (backend/status b)))))
    (testing "and so are later ones"
      (let [response (promise)]
        (backend/request! b "late" nil #(deliver response %))
        (is (nil? (deref response 0 :timeout)))))))

(deftest exiting-after-the-backend-has-stopped
  (let [stopped (promise)
        logged (atom [])
        respond (fn [_ msg]
                  (case (get msg "method")
                    "initialize" [(lsp/response (get msg "id") {"capabilities" {}})]
                    "shutdown" [(lsp/response (get msg "id") nil) fake/hang-up]
                    nil))
        b (backend/start! (backend/backend (spec))
                          (fake/connector (atom {}) respond)
                          {}
                          #(when (nil? %) (deliver stopped true)))]
    (is (eventually #(backend/ready? b)))
    (is (= true (deref (backend/shutdown! b) 2000 :timeout)))
    (is (= true (deref stopped 2000 :timeout)))
    (let [previous-writer (log/set-writer! #(swap! logged conj %))]
      (try
        (is (= 0 (backend/exit! b 1000)))
        (is (= [] @logged))
        (finally
          (log/set-writer! previous-writer))))))

(deftest exiting-a-backend-that-ignores-exit
  (testing "kills it once it has had `ms` to exit"
    (let [killed (promise)
          logged (atom [])
          connect (fn [_ on-message]
                    {:send #(when (= "initialize" (get % "method"))
                              (on-message (lsp/response (get % "id") {"capabilities" {}})))
                     :close (fn [])
                     :kill #(deliver killed true)
                     :wait (constantly nil)})
          b (backend/start! (backend/backend (spec)) connect {} (fn [_]))]
      (is (eventually #(backend/ready? b)))
      (let [previous-writer (log/set-writer! #(swap! logged conj %))]
        (try
          (is (nil? (backend/exit! b 50)))
          (finally
            (log/set-writer! previous-writer))))
      (is (realized? killed))
      (is (= [(str "heddle: " (:backend-id b) " hasn't exited after 50 ms and is being killed")] @logged))))
  (testing "a real process is gone once it returns"
    (let [b (backend/backend (spec))
          conn (backend/spawn ["sh" "-c" "trap '' TERM; sleep 30"] (temp-dir) (fn [_]))]
      (swap! (:state b) assoc :conn conn)
      (is (nil? (backend/exit! b 100)))
      (is (some? ((:wait conn) 0))))))

(deftest a-real-process
  (let [messages (java.util.concurrent.LinkedBlockingQueue.)
        b (backend/backend (prepares-with ["touch" "prepared-{proxy:backend_id}"]))
        conn (backend/connect-process b #(.put messages (or % :stopped)))]
    (testing "runs prepare then start in the spec's directory"
      (is (.exists (io/file (get-in b [:spec :dir]) (str "prepared-" (:backend-id b))))))
    (testing "frames messages both ways"
      ((:send conn) {"jsonrpc" "2.0" "method" "echo" "params" ["😀"]})
      (is (= {"jsonrpc" "2.0" "method" "echo" "params" ["😀"]}
             (.poll messages 2 java.util.concurrent.TimeUnit/SECONDS))))
    (testing "stops once its input is closed"
      ((:close conn))
      (is (= :stopped (.poll messages 2 java.util.concurrent.TimeUnit/SECONDS)))
      (is (= 0 ((:wait conn) 2000))))))
