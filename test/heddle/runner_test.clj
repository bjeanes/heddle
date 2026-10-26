(ns heddle.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.runner :as runner]))

(deftest builtins
  (is (= #{"host" "compose" "docker-exec" "docker-run"} (set (keys runner/builtins))))
  (doseq [[name r] runner/builtins]
    (is (empty? (runner/runner-errors r)) name))
  (is (= {"compose" ["docker" "compose"]} (get-in runner/builtins ["compose" "defaults"]))))

(defn- expand [template boot runtime]
  (let [r {"start" template}]
    (runner/expand r "start" (runner/context r boot "a" runtime))))

(deftest expansion
  (testing "strings fill placeholders anywhere in an element"
    (is (= ["--name=b1" "/r/a:/r"] (expand ["--name={proxy:backend_id}" "{client:scope}:{client:repo}"] {} {:repo "/r" :backend-id "b1"}))))
  (testing "lists splice in as whole elements"
    (is (= ["x" "1" "2" "y"] (expand ["x" "{cfg:l}" "y"] {"l" ["1" "2"]} {:repo "/r"})))
    (is (= ["x" "y"] (expand ["x" "{cfg:l}" "y"] {"l" []} {:repo "/r"}))))
  (testing "values can use placeholders other than cfg"
    (is (= ["/r/a:/srv/a"] (expand ["{cfg:v}"] {"v" "{client:scope}:{server:scope}" "paths" {"." "/srv"}} {:repo "/r"}))))
  (testing "text that isn't a placeholder stays as it is"
    (is (= ["{}" "{x}" "$1 \\"] (expand ["{}" "{x}" "$1 \\"] {} {:repo "/r"})))
    (is (= ["-x=$1 \\"] (expand ["-x={cfg:v}"] {"v" "$1 \\"} {:repo "/r"}))))
  (testing "problems are thrown together"
    (let [e (try (expand ["{cfg:a}" "-{cfg:l}" "{cfg:n}"] {"l" ["1"] "n" 1} {:repo "/r"})
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= ["no value for {cfg:a}"
              "{cfg:l} is a list, which has to make up a whole element"
              "{cfg:n} must be a string or a list of strings"]
             (:errors e))))))
