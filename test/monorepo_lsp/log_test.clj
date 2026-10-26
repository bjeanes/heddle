(ns monorepo-lsp.log-test
  (:require [clojure.test :refer [deftest is]]
            [monorepo-lsp.log :as log]))

(deftest writing-elsewhere
  (let [lines (atom [])
        previous (log/set-writer! #(swap! lines conj %))]
    (try
      (log/info "starting" "ruby" "for" (pr-str "api"))
      (finally
        (log/set-writer! previous)))
    (is (= ["monorepo-lsp: starting ruby for \"api\""] @lines))))
