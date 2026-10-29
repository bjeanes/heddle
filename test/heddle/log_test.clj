;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

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
