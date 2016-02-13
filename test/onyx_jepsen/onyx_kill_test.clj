(ns onyx-jepsen.onyx-kill-test
  (:require [clojure.test :refer :all]
            [jepsen.core :as jc]
            [jepsen.generator :as gen]
            [onyx-jepsen.gen :as onyx-gen]
            [onyx-jepsen.onyx-test :as onyx-test]))

(def version
  "What onyx version should we test?"
  "version-not-supplied")

(def test-name 
  "onyx-kill-test")

(def env-config
  (-> "resources/prod-env-config.edn" slurp read-string))

(def peer-config
  (-> "resources/prod-peer-config.edn" slurp read-string))

(def test-setup 
  {:job-params {:batch-size 1}
   :job-type :no-job
   :nemesis :crash-nemesis
   :client :no-bookkeeper
   :awake-secs 10
   :stopped-secs 10
   :stagger-start-stop-secs 200
   :time-limit 2000
   :n-jobs 0
   ; Minimum total = 5 (input ledgers) + 1 intermediate + 1 output
   :n-peers 20})

(def nothing-gen
  (->> (range)
       (map (fn [_] {:type :invoke, :f :nothing, :value nil}))
       gen/seq))

(defn generator [{:keys [job-type time-limit stagger-start-stop-secs awake-secs stopped-secs n-jobs job-params] :as test-setup}]
  (gen/phases
    (->> ;(gen/stagger 5 nothing-gen) 
         (gen/nemesis 
           ;gen/stagger stagger-start-stop-secs 
           (onyx-gen/start-stop-nemesis-seq awake-secs stopped-secs))
         (gen/time-limit time-limit)) 

    ;; Bring everything back at the end
    ;; This way we can test that the peers came back up

    ;; Remove this later and just calculate by the ones that were restarted
    (gen/nemesis (gen/once {:type :info :f :start}))
    (gen/nemesis (gen/once {:type :info :f :stop}))

    ;; Sleep for a while to give peers a chance to come back up
    ;; Should be enough time that curator backoff * max-retries is covered
    (gen/sleep 120)

    (onyx-gen/read-peer-log-gen)))

(deftest basic-test
  (is (-> (onyx-test/jepsen-test env-config peer-config test-setup test-name version (generator test-setup))
          jc/run!
          :results
          :valid?)))
