(ns onyx-jepsen.core-test
  (:require [clojure.test :refer :all]
            [onyx-jepsen.core :as oj]
            [jepsen.core :as jc]
            [jepsen.control :as c]))

(def version
  "What meowdb version should we test?"
  "1.2.3")

(deftest basic-test
  (is (:valid? (:results (jc/run! (oj/basic-test version))))))
