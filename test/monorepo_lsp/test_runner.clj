(ns monorepo-lsp.test-runner
  (:require [clojure.test :as t]
            [monorepo-lsp.config-test]
            [monorepo-lsp.glob-test]
            [monorepo-lsp.json-test]
            [monorepo-lsp.lsp-test]
            [monorepo-lsp.paths-test]
            [monorepo-lsp.runner-test]
            [monorepo-lsp.translate-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-all-tests #"monorepo-lsp\..*-test")]
    (System/exit (if (zero? (+ fail error)) 0 1))))
