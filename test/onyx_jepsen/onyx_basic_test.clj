(ns onyx-jepsen.onyx-basic-test
  (:require [clojure.test :refer :all]
            [onyx-jepsen.core :as oj]
            [jepsen.core :as jc]
            [jepsen.control :as c]))

(def version
  "What onyx version should we test?"
  "version-not-supplied")

(def env-config
  (-> "resources/prod-env-config.edn" slurp read-string))

(def peer-config
  (-> "resources/prod-peer-config.edn" slurp read-string))

(def test-setup 
  {:job-params {:batch-size 1}
   :nemesis :random-halves ; :bridge-shuffle or :random-halves
   :time-limit 200
   ; may or may not work when 5 is not divisible by n-jobs
   :n-jobs 1
   ; Minimum total = 5 (input ledgers) + 1 intermediate + 1 output
   :n-peers 3})

(deftest basic-test
  (is (:valid? (:results (jc/run! (oj/basic-test env-config peer-config test-setup version))))))
