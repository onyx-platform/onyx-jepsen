(ns onyx-peers.tasks.bookkeeper
  (:require [taoensso.timbre :refer [info error debug fatal]]))

(defn add-read-ledgers [job to-task batch-size zk-addr ledgers-root-path password ledger-ids]
  (let [read-ledger-task-names (mapv (fn [id]
                                       (keyword (str "read-ledger-" id)))
                                     ledger-ids)]
    (-> job 
        (update :workflow
                into
                (mapv (fn [task] [task to-task])
                      read-ledger-task-names))
        (update :catalog 
                into 
                (mapv (fn [task ledger-id]
                        {:onyx/name task
                         :onyx/plugin :onyx.plugin.bookkeeper/read-ledgers
                         :onyx/type :input
                         :onyx/medium :bookkeeper
                         ;; TODO: Vary pending timeout in different Jepsen tests
                         :onyx/max-pending 5000
                         :onyx/pending-timeout 10000
                         ;; TODO: Vary read max chunk in different tests
                         ;:bookkeeper/read-max-chunk-size 10
                         :bookkeeper/zookeeper-addr zk-addr
                         :bookkeeper/zookeeper-ledgers-root-path ledgers-root-path
                         :bookkeeper/ledger-id ledger-id
                         :bookkeeper/digest-type :mac
                         :bookkeeper/deserializer-fn :onyx.compression.nippy/zookeeper-decompress
                         :bookkeeper/password-bytes password 
                         :bookkeeper/no-recovery? true
                         :onyx/max-peers 1
                         :onyx/batch-size batch-size
                         :onyx/doc "Reads a sequence from a BookKeeper ledger"})
                      read-ledger-task-names
                      ledger-ids))
        (update :lifecycles 
                into 
                (mapcat (fn [task]
                          [{:lifecycle/task task
                            :lifecycle/calls :onyx.plugin.bookkeeper/read-ledgers-calls}])
                        read-ledger-task-names)))))
