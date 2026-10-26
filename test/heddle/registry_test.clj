(ns heddle.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.registry :as registry]))

(def ruby {:backend-id "ruby-1" :spec {:slot ["ruby" "."]}})
(def k ["ruby" "."])

(deftest retrying-a-failed-slot
  (let [failed (-> registry/empty-registry
                   (registry/started ruby)
                   (registry/failed ruby "ruby in the repo root failed" 1000))]
    (testing "any time if it hasn't failed"
      (is (registry/may-start? registry/empty-registry k 0)))
    (testing "only once the retry interval has passed since it failed"
      (is (not (registry/may-start? failed k (+ 1000 registry/retry-interval-ms -1))))
      (is (registry/may-start? failed k (+ 1000 registry/retry-interval-ms))))
    (testing "with why it failed, while it isn't live"
      (is (= ["ruby in the repo root failed"] (registry/failures failed [k ["lint" "."]])))
      (is (empty? (registry/failures (registry/started failed ruby) [k]))))))

(deftest failing-a-backend-that-was-replaced
  (let [restarted (assoc ruby :backend-id "ruby-2")
        registry (-> registry/empty-registry
                     (registry/started ruby)
                     (registry/started restarted))]
    (testing "leaves its replacement live"
      (is (= registry (registry/failed registry ruby "stopped" 1000))))))

(deftest reporting-on-a-slot
  (let [reported (registry/reported registry/empty-registry k 1000)]
    (testing "is due if the user hasn't been told about it"
      (is (registry/report-due? registry/empty-registry k 0)))
    (testing "is due again once the report interval has passed"
      (is (not (registry/report-due? reported k (+ 1000 registry/report-interval-ms -1))))
      (is (registry/report-due? reported k (+ 1000 registry/report-interval-ms))))
    (testing "keeps the time of the last report that was due"
      (is (= reported (registry/reported reported k 2000))))))

(deftest opening-documents
  (let [registry (registry/started registry/empty-registry ruby)
        opened (registry/opened registry ruby "file:///repo/a.rb")]
    (testing "in a live backend"
      (is (registry/opened? opened ruby "file:///repo/a.rb"))
      (is (not (registry/opened? opened ruby "file:///repo/b.rb"))))
    (testing "in no backend once closed"
      (is (not (registry/opened? (registry/closed opened "file:///repo/a.rb") ruby "file:///repo/a.rb"))))
    (testing "in a backend that isn't live does nothing"
      (is (= registry/empty-registry (registry/opened registry/empty-registry ruby "file:///repo/a.rb"))))))
