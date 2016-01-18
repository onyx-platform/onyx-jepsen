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

(deftest basic-test
  (is (:valid? (:results (jc/run! (oj/basic-test env-config peer-config version))))))
