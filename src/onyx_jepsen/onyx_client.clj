(ns onyx-jepsen.onyx-client
  "Tests for Onyx"
  (:require [clojure.core.async :as casync :refer [alts!! chan]]
            [clojure.tools.logging :refer :all]
            [com.stuartsierra.component :as component]
            [jepsen.client :as client]
            [jepsen.util :refer [timeout]]
            [onyx-jepsen.simple-job :as simple-job]
            [onyx.api]
            [onyx.plugin.bookkeeper]
            [onyx.compression.nippy :as nippy]
            [onyx.state.log.bookkeeper :as obk])
  (:import (org.apache.bookkeeper.client LedgerEntry LedgerHandle)))

(defn bookkeeper-client [env-config]
  (obk/bookkeeper env-config))

(defn read-ledger-entries [env-config ledger-id]
  (let [client (bookkeeper-client env-config)
        pwd (obk/password env-config)]
    (let [ledger-handle (obk/open-ledger client ledger-id obk/digest-type pwd)
          results (try 
                    (let [last-confirmed (.getLastAddConfirmed ledger-handle)]
                      (info "last confirmed " last-confirmed " ledger-id " ledger-id)
                      (if-not (neg? last-confirmed)
                        (loop [results [] 
                               entries (.readEntries ledger-handle 0 last-confirmed)
                               element ^LedgerEntry (.nextElement entries)] 
                          (let [new-results (conj results (nippy/zookeeper-decompress (.getEntry element)))] 
                            (if (.hasMoreElements entries)
                              (recur new-results entries (.nextElement entries))
                              new-results)))
                        []))
                    (catch Throwable t
                      (throw 
                        (ex-info (str t (.getCause t))
                                 {:ledger-id ledger-id :exception t})))
                    (finally 
                      (.close client)
                      (.close ledger-handle)))]
      {:ledger-id ledger-id
       :results results})))

(defn get-written-ledgers [onyx-client job-data onyx-id task-name]
  (onyx.plugin.bookkeeper/read-ledgers-data (:log onyx-client) 
                                            onyx-id 
                                            (:job-id job-data)
                                            (get-in job-data [:task-ids task-name :id])))


(defn close-ledger-handles [ledger-handles]
  (mapv (fn [h] 
          (try (.close h)
               (catch Throwable t
                 (info "Error closing handle" t)))) 
        ledger-handles))

(defn await-jobs-completions [peer-config jobs-data]
  (mapv (fn [[job-num job-data]]
          (onyx.api/await-job-completion peer-config (:job-id job-data)))
        jobs-data)) 

(defn assigned-ledgers [ledger-ids job-num n-jobs]
  (nth (partition-all (/ (count ledger-ids) 
                         n-jobs) 
                      ledger-ids)
       job-num))

(def max-entries 50000)

(defn read-peer-log [log timeout-ms]
  (let [ch (chan 1000)] 
    (onyx.extensions/subscribe-to-log log ch)
    (loop [entries []]
      (if (= 50000 (count entries)) 
        entries
        (if-let [entry (first (alts!! [ch (casync/timeout timeout-ms)]))]
          (do (info "LOG ENTRY:" entry)
              (recur (conj entries entry)))
          entries)))))

(defn read-task-ledgers [onyx-client env-config jobs onyx-id task-name]
  (into {} 
        (map (fn [[job-num job-data]] 
               (vector job-num 
                       (mapv (partial read-ledger-entries env-config) 
                             (get-written-ledgers onyx-client job-data onyx-id task-name))))
             jobs)))

(defrecord PeerReadClient [env-config peer-config onyx-client]
  client/Client
  (setup! [this test node]
    (let [onyx-client (component/start (onyx.system/onyx-client peer-config))]
      (assoc this :onyx-client onyx-client)))

  (invoke! [this test op]
    (case (:f op)
      :nothing (assoc op :type :ok)
      :read-peer-log (timeout 1000000
                              (assoc op :type :info :value :timed-out)
                              (try
                                (assoc op 
                                       :type :ok 
                                       :value (read-peer-log (:log onyx-client) (or (:timeout-ms op) 20000)))
                                (catch Throwable t
                                  (assoc op :type :info :value t))))))

  (teardown! [_ test]
    (component/stop onyx-client)))

(defn new-peer-read-client [env-config peer-config]
  (->PeerReadClient env-config peer-config nil))

;; TODO, merge jobs-data, ledger-handles, and ledger-ids atoms into one big atom map
(defrecord WriteLogClient [env-config peer-config client jobs-data ledger-handles ledger-handle ledger-ids onyx-client]
  client/Client
  (setup! [this test node]
    (let [client (bookkeeper-client env-config)
          lh (obk/new-ledger client env-config)
          onyx-client (component/start (onyx.system/onyx-client peer-config))]
      (swap! ledger-ids conj (.getId lh))
      (swap! ledger-handles conj lh)
      (assoc this :client client :ledger-handle lh :onyx-client onyx-client)))

  (invoke! [this test op]
    (let [zk-addr (:zookeeper/address env-config)
          onyx-id (:onyx/id env-config)] 
      (case (:f op)
        :read-peer-log (timeout 1000000
                                (assoc op :type :info :value :timed-out)
                                (try
                                  (assoc op 
                                         :type :ok 
                                         :value (read-peer-log (:log onyx-client) (or (:timeout-ms op) 20000)))
                                  (catch Throwable t
                                    (assoc op :type :info :value t))))

        :close-ledgers-await-completion (timeout 1000000
                                                 (assoc op :type :info :value :timed-out)
                                                 (try
                                                   (assoc op 
                                                          :type :ok 
                                                          :value (do (close-ledger-handles @ledger-handles)
                                                                     (await-jobs-completions peer-config @jobs-data)
                                                                     ; to debug, nil to ensure it's serializable for now
                                                                     nil))
                                                   (catch Throwable t
                                                     (assoc op :type :info :value t))))

        :read-ledgers (timeout 1000000
                               (assoc op :type :info :value :timed-out)
                               (try
                                 (assoc op 
                                        :type :ok 
                                        :value (read-task-ledgers onyx-client env-config @jobs-data onyx-id (:task op)))
                                 (catch Throwable t
                                   (assoc op :type :info :value t))))

        :gc-peer-log (timeout 10000
                              (assoc op :type :info :value :timed-out)
                              (try
                                (assoc op
                                       :type :ok
                                       :value (onyx.api/gc peer-config))
                                (catch Throwable t
                                  (assoc op :type :info :value t))))

        :submit-job (timeout 10000
                             (assoc op :type :info :value :timed-out)
                             (try
                               (assoc op
                                      :type :ok
                                      :value (let [{:keys [job-num n-jobs params]} op
                                                   ledgers (assigned-ledgers @ledger-ids job-num n-jobs)
                                                   built-job (case (:job-type op) 
                                                               :window-state-job 
                                                               (simple-job/build-window-state-job job-num 
                                                                                                  params
                                                                                                  zk-addr
                                                                                                  (onyx.log.zookeeper/ledgers-path onyx-id)
                                                                                                  ledgers)
                                                               :simple-job
                                                               (simple-job/build-job job-num 
                                                                                     params
                                                                                     zk-addr
                                                                                     (onyx.log.zookeeper/ledgers-path onyx-id)
                                                                                     ledgers))
                                                   job-data (onyx.api/submit-job peer-config built-job)]
                                               (swap! jobs-data assoc job-num job-data)
                                               job-data))
                               (catch Throwable t
                                 (assoc op :type :info :value t)))) 

        :add (timeout 5000 
                      (assoc op :type :info :value :timed-out)
                      (try
                        (do 
                          (.addEntry ledger-handle (nippy/zookeeper-compress (:value op)))
                          (assoc op :type :ok :ledger-id (.getId ledger-handle)))
                        (catch Throwable t
                          (assoc op :type :info :value (.getMessage t))))))))

  (teardown! [_ test]
    (.close client)
    (component/stop onyx-client)))

(defn write-log-client [env-config peer-config jobs-data ledger-handles ledger-ids]
  (map->WriteLogClient {:env-config env-config :peer-config peer-config
                        :jobs-data jobs-data :ledger-handles ledger-handles :ledger-ids ledger-ids}))
