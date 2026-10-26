(ns heddle.process-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.process :as process]))

(defn- run-timed [command timeout-ms]
  (let [started (System/currentTimeMillis)
        result (process/run command "." {:timeout-ms timeout-ms})]
    [result (- (System/currentTimeMillis) started)]))

(deftest running-to-completion
  (is (= {:exit 3 :output "out\nerr\n"}
         (process/run ["sh" "-c" "echo out; echo err >&2; exit 3"] "." {:timeout-ms 5000}))))

(deftest running-out-of-time
  (testing "stops a command that hangs, and what it started, keeping what it printed"
    (let [[result ms] (run-timed ["sh" "-c" "echo started; sleep 30; echo finished"] 200)]
      (is (= {:timed-out true :output "started\n"} result))
      (is (< ms 2000))))
  (testing "forces a command that ignores being asked to stop"
    (let [[result ms] (run-timed ["sh" "-c" "trap '' TERM; exec sleep 30"] 200)]
      (is (:timed-out result))
      (is (< ms 5000)))))

(deftest durations
  (is (= "120 s" (process/duration 120000)))
  (is (= "95 s" (process/duration 95321)))
  (is (= "1043 ms" (process/duration 1043)))
  (is (= "250 ms" (process/duration 250))))
