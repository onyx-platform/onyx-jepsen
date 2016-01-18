(ns onyx-jepsen.checker
  (:require [onyx.extensions]
            [jepsen [checker :as checker]]
            [onyx.system :as system]
            [onyx.log.zookeeper]
            [onyx.log.curator]
            [clojure.stacktrace]
            [com.stuartsierra.component :as component]
            [onyx.log.replica :as replica]
            [clojure.core.async :as casync :refer [chan >!! <!! close! alts!!]]
            [clojure.tools.logging :refer :all]))

(defn read-peer-log [log timeout-ms]
  (let [ch (chan 1000)] 
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

(defn playback-log [peer-config peer-log-reads]
  (reduce #(onyx.extensions/apply-log-entry %2 %1)
          (base-replica peer-config)
          peer-log-reads))

(defn pulses [conn peer-config]
  (onyx.log.curator/children conn (onyx.log.zookeeper/pulse-path (:onyx/id peer-config))))

;; TODO, check whether the jobs were even submitted, if not, nothing should be read back 
;; important for short running tests
(defrecord Checker [peer-config n-peers n-jobs]
  checker/Checker
  (check [checker test model history]
    (let [;;;;;;;;;
          ;; Cluster invariants
          peer-log-reads (:value (first (filter (fn [action]
                                                  (and (= (:f action) :read-peer-log)
                                                       (= (:type action) :ok)))
                                                          history)))
          final-replica (playback-log peer-config peer-log-reads)
          log-conn (:log (component/start (system/onyx-client peer-config)))
          all-peers-up? (= (count (:peers final-replica))
                           (* 5 n-peers))
          pulse-peers (pulses (:conn log-conn) peer-config)

          peers-match-pulses? (= (sort (map str (:peers final-replica)))
                                 (sort pulse-peers))

          accepting-empty? (empty? (:accepted final-replica))
          prepared-empty? (empty? (:prepared final-replica))

          cluster-invariants {:all-peers-up? all-peers-up? 
                              :peers-match-pulses? peers-match-pulses? 
                              :accepting-empty? accepting-empty? 
                              :prepared-empty? prepared-empty?}

          ;;;;;;;;;
          ;; Job invariants

          exception (if-let [killed-job (first (:killed-jobs final-replica))] 
                      (onyx.extensions/read-chunk log-conn :exception killed-job))

          ledger-reads (first (filter (fn [action]
                                        (and (= (:f action) :read-ledgers)
                                             (= (:type action) :ok)))
                                      history))


          ledger-read-results (->> ledger-reads
                                   :value
                                   (mapcat (fn [[job-num reads]]
                                             (map (fn [r] 
                                                    {:job-num job-num
                                                     :read-results (:results r)}) 
                                                  reads))))

          ;; Add a check here that there are no overlaps in the ledgers read by the jobs
          reads-correct-jobs? (empty? 
                                (remove (fn [{:keys [read-results job-num]}]
                                          (or (empty? read-results) 
                                              (= #{job-num} (set (map :job-num read-results)))))
                                        ledger-read-results))

          successfully-added (filter (fn [action]
                                       (and (= (:f action) :add)
                                            (= (:type action) :ok)))
                                     history)

          added-values (set (map :value successfully-added))

          read-values (->> ledger-read-results
                           (mapcat :read-results)
                           (map :value)
                           set)

          diff-written-read (clojure.set/difference added-values read-values)
          all-written-read? (empty? diff-written-read)

          ;; Check that all values at the output went through the second task first
          all-job-set (set (mapcat (fn [lr] 
                                     (map :job-num (:results lr))) 
                                   (:value ledger-reads)))
          all-through-intermediate? (= all-job-set (set (range 0 n-jobs)))
          unacked-writes-read (clojure.set/difference read-values added-values)
          job-invariants {:job-completed? (nil? exception)
                          :reads-correct-jobs? reads-correct-jobs?
                          :all-written-read? all-written-read?}
          invariants [job-invariants cluster-invariants]]
      (doto {:valid? (empty? (filter false? (apply concat (map vals invariants))))
             :job-invariants job-invariants
             :cluster-invariants cluster-invariants
             :run-information {:added added-values
                               :read-values read-values
                               :diff-written-read diff-written-read
                               :unacknowledged-writes-read unacked-writes-read
                               :job-exception-message (some-> exception (.getMessage))
                               :job-exception-trace (if exception (with-out-str (clojure.stacktrace/print-stack-trace exception)))
                               :pulse-peers pulse-peers
                               :peer-log peer-log-reads
                               :final-replica final-replica}}
        info))))
