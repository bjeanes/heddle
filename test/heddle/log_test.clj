(ns heddle.log-test
  (:require [clojure.test :refer [deftest is]]
            [heddle.log :as log]))

(deftest writing-elsewhere
  (let [lines (atom [])
        previous (log/set-writer! #(swap! lines conj %))]
    (try
      (log/info "starting" "ruby" "for" (pr-str "api"))
      (finally
        (log/set-writer! previous)))
    (is (= ["heddle: starting ruby for \"api\""] @lines))))
