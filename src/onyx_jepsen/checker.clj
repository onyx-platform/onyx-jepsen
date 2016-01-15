(ns onyx-jepsen.checker
  (:require [onyx.extensions]
            [jepsen [checker :as checker]]
            [onyx.system :as system]
            [onyx.log.zookeeper]
            [onyx.log.curator]
            [com.stuartsierra.component :as component]
            [onyx.log.replica :as replica]
            [clojure.core.async :as casync :refer [chan >!! <!! close! alts!!]]
            [clojure.tools.logging :refer :all]))

(defn read-peer-log [log]
  (let [ch (chan 1000)
        timeout-ms 20000] 
    (onyx.extensions/subscribe-to-log log ch)
    (loop [entries []]
      (if-let [entry (first (alts!! [ch (casync/timeout timeout-ms)]))]
        (do (info "Read " entry)
            (recur (conj entries entry)))
        entries))))

(defn base-replica [peer-config]
  (merge replica/base-replica 
         {:job-scheduler (:onyx.peer/job-scheduler peer-config)
          :messaging (select-keys peer-config [:onyx.messaging/impl])}))

(defrecord Checker [peer-config n-peers]
  checker/Checker
  (check [checker test model history]
    (let [ledger-reads (first (filter (fn [action]
                                        (and (= (:f action) :read-ledgers)
                                             (= (:type action) :ok)))
                                      history))
          peer-log-reads (:value (first (filter (fn [action]
                                                  (and (= (:f action) :read-peer-log)
                                                       (= (:type action) :ok)))
                                                history)))
          final-replica (reduce #(onyx.extensions/apply-log-entry %2 %1)
                                (base-replica peer-config)
                                peer-log-reads) 

          all-peers-up? (= (count (:peers final-replica))
                           (* 5 n-peers))

          results (map (fn [lr] 
                         (map :value (:results lr))) 
                       (:value ledger-reads))

          successfully-added (filter (fn [action]
                                       (and (= (:f action) :add)
                                            (= (:type action) :ok)))
                                     history)
          added-values (set (map :value successfully-added))
          read-values (set (reduce into [] results))
          diff-written-read (clojure.set/difference added-values read-values)
          all-written-read? (empty? diff-written-read)

          ;; Check that all values at the output went through the second task first
          all-stage-2 (set (mapcat (fn [lr] 
                                     (map :stage (:results lr))) 
                                   (:value ledger-reads)))
          all-through-intermediate? (= all-stage-2 #{2})
          unacked-writes-read (clojure.set/difference read-values added-values)]
      {:valid? (and all-written-read? all-peers-up?)
       :peer-log peer-log-reads
       :final-replica final-replica
       :added added-values
       :read-values read-values
       :diff-written-read diff-written-read
       :unacknowledged-writes-read unacked-writes-read
       :all-peers-up? all-peers-up?
       :all-written-read? all-written-read?})))
