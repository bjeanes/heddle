(ns heddle.channel-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.channel :as channel]
            [heddle.lsp :as lsp]))

(defn- recording []
  (let [sent (atom [])]
    [sent (channel/channel #(swap! sent conj %))]))

(deftest holding-messages
  (let [[sent ch] (recording)
        a (lsp/notification "a" nil)
        b (lsp/request 1 "b" nil)
        answer (lsp/response 0 {})
        c (lsp/notification "c" nil)]
    (channel/hold! ch)
    (channel/send! ch a)
    (channel/send! ch b)
    (testing "keeps them while held"
      (is (channel/held? ch))
      (is (= [] @sent)))
    (testing "sends what it's opened with first, then what it kept, in order"
      (channel/open! ch answer)
      (is (= [answer a b] @sent)))
    (testing "then sends straight away"
      (channel/send! ch c)
      (is (not (channel/held? ch)))
      (is (= [answer a b c] @sent)))))

(deftest requests-to-the-client
  (let [[sent ch] (recording)
        answers (atom [])]
    (channel/request! ch "m" {} :x #(swap! answers conj %))
    (channel/request! ch "m" {} :y #(swap! answers conj %))
    (testing "get IDs of the channel's own"
      (is (= [(lsp/request 1 "m" {}) (lsp/request 2 "m" {})] @sent)))
    (testing "can be found by their tags while open"
      (is (= [2] (channel/tagged-requests ch :y))))
    (testing "are called back with the response, once"
      (is (true? (channel/answered! ch (lsp/response 2 "ok"))))
      (is (false? (channel/answered! ch (lsp/response 2 "again"))))
      (is (= [(lsp/response 2 "ok")] @answers))
      (is (= [] (channel/tagged-requests ch :y))))))

(deftest sending-serially
  (let [[sent ch] (recording)
        first-msg (lsp/notification "first" nil)
        second-msg (lsp/notification "second" nil)
        inside (promise)
        finish (promise)]
    (let [worker (future (channel/serially ch #(do (deliver inside true)
                                                    @finish
                                                    (channel/send! ch first-msg))))
          _ @inside
          other (future (channel/send! ch second-msg))]
      (Thread/sleep 50)
      (testing "keeps others' messages out until it's done"
        (is (= [] @sent)))
      (deliver finish true)
      @worker
      @other
      (is (= [first-msg second-msg] @sent)))))
