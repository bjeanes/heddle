;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.test-runner
  (:require [clojure.test :as t]
            [heddle.backend-test]
            [heddle.capabilities-test]
            [heddle.channel-test]
            [heddle.config-test]
            [heddle.diagnostics-test]
            [heddle.documents-test]
            [heddle.fetched-test]
            [heddle.glob-test]
            [heddle.json-test]
            [heddle.licence-test]
            [heddle.log :as log]
            [heddle.log-test]
            [heddle.lsp-test]
            [heddle.main-test]
            [heddle.merge-test]
            [heddle.methods-test]
            [heddle.normalise-test]
            [heddle.origin-test]
            [heddle.paths-test]
            [heddle.process-test]
            [heddle.proxy-test]
            [heddle.readme-test]
            [heddle.registry-test]
            [heddle.requests-test]
            [heddle.routing-test]
            [heddle.runner-test]
            [heddle.session-test]
            [heddle.spec-test]
            [heddle.starter-test]
            [heddle.toml-writer-test]
            [heddle.trace-test]
            [heddle.translate-test]))

(defn -main [& _]
  (log/set-writer! (fn [_]))
  (let [{:keys [fail error]} (t/run-all-tests #"heddle\..*-test")]
    (System/exit (if (zero? (+ fail error)) 0 1))))
