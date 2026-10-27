(ns heddle.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.runner :as runner]))

(defn- expand
  "Expands `template` for a boot config in the scope a of the repo /r, which
  the server sees as /srv/a."
  ([template boot] (expand template boot {}))
  ([template boot runtime]
   (let [r {"start" template}
         scope {:repo "/r" :dir "/r/a" :root "/srv/a"}]
     (runner/expand r "start" (runner/context (runner/cfg boot) scope runtime)))))

(deftest expansion
  (testing "strings fill placeholders anywhere in an element"
    (is (= ["--name=b1" "/r/a:/r"] (expand ["--name={proxy:backend_id}" "{client:scope}:{client:repo}"] {} {:backend-id "b1"}))))
  (testing "lists splice in as whole elements"
    (is (= ["x" "1" "2" "y"] (expand ["x" "{cfg:l}" "y"] {"l" ["1" "2"]})))
    (is (= ["x" "y"] (expand ["x" "{cfg:l}" "y"] {"l" []}))))
  (testing "values can use placeholders other than cfg"
    (is (= ["/r/a:/srv/a"] (expand ["{cfg:v}"] {"v" "{client:scope}:{server:scope}"}))))
  (testing "text that isn't a placeholder stays as it is"
    (is (= ["{}" "{x}" "$1 \\"] (expand ["{}" "{x}" "$1 \\"] {})))
    (is (= ["-x=$1 \\"] (expand ["-x={cfg:v}"] {"v" "$1 \\"}))))
  (testing "problems are thrown together"
    (let [e (try (expand ["{cfg:a}" "-{cfg:l}" "{cfg:n}"] {"l" ["1"] "n" 1})
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= ["no value for {cfg:a}"
              "{cfg:l} is a list, which has to make up a whole element"
              "{cfg:n} must be a string or a list of strings"]
             (:errors e))))))
