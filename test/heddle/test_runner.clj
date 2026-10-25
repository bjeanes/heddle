(ns heddle.test-runner
  (:require [clojure.test :as t]
            [heddle.backend-test]
            [heddle.capabilities-test]
            [heddle.config-test]
            [heddle.fetched-test]
            [heddle.glob-test]
            [heddle.json-test]
            [heddle.log :as log]
            [heddle.log-test]
            [heddle.lsp-test]
            [heddle.main-test]
            [heddle.merge-test]
            [heddle.normalise-test]
            [heddle.origin-test]
            [heddle.paths-test]
            [heddle.process-test]
            [heddle.proxy-test]
            [heddle.readme-test]
            [heddle.runner-test]
            [heddle.starter-test]
            [heddle.translate-test]))

(defn -main [& _]
  (log/set-writer! (fn [_]))
  (let [{:keys [fail error]} (t/run-all-tests #"heddle\..*-test")]
    (System/exit (if (zero? (+ fail error)) 0 1))))
