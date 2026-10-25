(ns monorepo-lsp.test-runner
  (:require [clojure.test :as t]
            [monorepo-lsp.backend-test]
            [monorepo-lsp.capabilities-test]
            [monorepo-lsp.config-test]
            [monorepo-lsp.fetched-test]
            [monorepo-lsp.glob-test]
            [monorepo-lsp.json-test]
            [monorepo-lsp.log :as log]
            [monorepo-lsp.log-test]
            [monorepo-lsp.lsp-test]
            [monorepo-lsp.main-test]
            [monorepo-lsp.merge-test]
            [monorepo-lsp.origin-test]
            [monorepo-lsp.paths-test]
            [monorepo-lsp.proxy-test]
            [monorepo-lsp.readme-test]
            [monorepo-lsp.runner-test]
            [monorepo-lsp.translate-test]))

(defn -main [& _]
  (log/set-writer! (fn [_]))
  (let [{:keys [fail error]} (t/run-all-tests #"monorepo-lsp\..*-test")]
    (System/exit (if (zero? (+ fail error)) 0 1))))
