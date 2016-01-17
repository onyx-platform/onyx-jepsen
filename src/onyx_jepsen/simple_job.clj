(ns onyx-jepsen.simple-job
  (:require [taoensso.timbre :refer [info error debug fatal]]))


(defn add-read-ledgers [job batch-size zk-addr ledgers-root-path password ledger-ids]
  (let [read-ledger-task-names (mapv (fn [id]
                                       (keyword (str "read-ledger" id)))
                                     ledger-ids)]
    (-> job 
        (update :workflow
                into
                (mapv (fn [task] [task :identity-log])
                      read-ledger-task-names))
        (update :catalog 
                into 
                (mapv (fn [task ledger-id]
                        {:onyx/name task
                         :onyx/plugin :onyx.plugin.bookkeeper/read-ledgers
                         :onyx/type :input
                         :onyx/medium :bookkeeper
                         ;; TODO: Vary pending timeout in different Jepsen tests
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
                         :onyx/restart-pred-fn :onyx-peers.functions.functions/restartable?
                         :onyx/max-peers 1
                         :onyx/batch-size batch-size
                         :onyx/doc "Reads a sequence from a BookKeeper ledger"})
                      read-ledger-task-names
                      ledger-ids))
        (update :lifecycles 
                into 
                (mapv (fn [task]
                        {:lifecycle/task task
                         :lifecycle/calls :onyx.plugin.bookkeeper/read-ledgers-calls})
                      read-ledger-task-names)))))

(defn build-job [job-num {:keys [batch-size] :as params} zk-addr ledgers-root-path ledger-ids]
  (let [password (.getBytes "INSECUREDEFAULTPASSWORD")
        job {:catalog [{:onyx/name :persist
                        :onyx/plugin :onyx.plugin.bookkeeper/write-ledger
                        :onyx/type :output
                        :onyx/medium :bookkeeper
                        :onyx/restart-pred-fn :onyx-peers.functions.functions/restartable?
                        :bookkeeper/serializer-fn :onyx.compression.nippy/zookeeper-compress
                        :bookkeeper/password-bytes password
                        :bookkeeper/ensemble-size 3
                        :bookkeeper/quorum-size 3
                        :bookkeeper/zookeeper-addr zk-addr
                        :bookkeeper/digest-type :mac
                        :onyx/batch-size batch-size
                        :onyx/doc "Writes messages to a BookKeeper ledger"}
                       {:onyx/name :identity-log
                        :onyx/fn :onyx-peers.functions.functions/add-job-num
                        :jepsen/job-num job-num
                        :onyx/params [:jepsen/job-num]
                        :onyx/restart-pred-fn :onyx-peers.functions.functions/restartable?
                        :onyx/type :function
                        :onyx/batch-size batch-size}]
             :lifecycles [{:lifecycle/task :persist
                           :lifecycle/calls :onyx.plugin.bookkeeper/write-ledger-calls}]
             :workflow [[:identity-log :persist]]
             :task-scheduler :onyx.task-scheduler/balanced}] 
    (add-read-ledgers job batch-size zk-addr ledgers-root-path password ledger-ids)))
