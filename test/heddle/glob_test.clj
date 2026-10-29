(ns heddle.glob-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.glob :as glob]))

(deftest matches
  (testing "** matches any number of whole segments"
    (is (glob/matches? "**/Gemfile" "Gemfile"))
    (is (glob/matches? "**/Gemfile" "services/api/Gemfile"))
    (is (not (glob/matches? "**/Gemfile" "services/api/Gemfile.lock")))
    (is (glob/matches? "services/**" "services/api/x.rb"))
    (is (glob/matches? "a/**/b" "a/b"))
    (is (glob/matches? "a/**/b" "a/x/y/b")))
  (testing "* and ? stay within a segment"
    (is (glob/matches? "*.rake" "build.rake"))
    (is (not (glob/matches? "*.rake" "lib/build.rake")))
    (is (glob/matches? "lib/?.rb" "lib/a.rb"))
    (is (not (glob/matches? "lib/?.rb" "lib/ab.rb"))))
  (testing "other characters are literal"
    (is (glob/matches? "a+b.(c)" "a+b.(c)"))
    (is (not (glob/matches? "a.rb" "abrb")))))

(deftest literal
  (testing "metacharacters go in brackets"
    (is (= "/r/[*]/[?]/[[]/[\\]" (glob/literal "/r/*/?/[/\\"))))
  (testing "braces and closing brackets match any character"
    (is (= "/r/a?b?c?" (glob/literal "/r/a{b}c]"))))
  (testing "anything else is left alone"
    (is (= "/r/services/api-v2, old!" (glob/literal "/r/services/api-v2, old!")))))
