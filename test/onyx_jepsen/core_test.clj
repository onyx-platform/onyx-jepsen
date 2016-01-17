(ns onyx-jepsen.core-test
  (:require [clojure.test :refer :all]
            [onyx-jepsen.core :as oj]
            [jepsen.core :as jc]
            [jepsen.control :as c]))

(def version
  "What onyx version should we test?"
  "version-not-supplied")

(deftest basic-test
  (is (:valid? (:results (jc/run! (oj/basic-test version))))))
