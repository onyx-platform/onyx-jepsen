(ns onyx-jepsen.simple-job
  (:require [onyx-peers.tasks.bookkeeper :refer [add-read-ledgers]]
            [fipp.edn]
            [taoensso.timbre :refer [info error debug fatal]]))

(defn build-job [job-num {:keys [batch-size] :as params} zk-addr ledgers-root-path ledger-ids]
  (let [password (.getBytes "INSECUREDEFAULTPASSWORD")
        job (-> {:catalog [{:onyx/name :persist
                            :onyx/plugin :onyx.plugin.bookkeeper/write-ledger
                            :onyx/type :output
                            :onyx/medium :bookkeeper
                            :bookkeeper/serializer-fn :onyx.compression.nippy/zookeeper-compress
                            :bookkeeper/password-bytes password
                            :bookkeeper/ensemble-size 3
                            :bookkeeper/quorum-size 3
                            :bookkeeper/zookeeper-addr zk-addr
                            :bookkeeper/digest-type :mac
                            :onyx/batch-size batch-size
                            :onyx/doc "Writes messages to a BookKeeper ledger"}
                           {:onyx/name :annotate-job
                            :onyx/fn :onyx-peers.functions.functions/annotate-job-num
                            :jepsen/job-num job-num
                            :onyx/params [:jepsen/job-num]
                            :onyx/type :function
                            :onyx/batch-size batch-size}]
                 :lifecycles [#_{:lifecycle/task :all 
                               :lifecycle/calls :onyx.lifecycle.metrics.metrics/calls
                               :metrics/buffer-capacity 10000
                               :metrics/workflow-name "simple-job"
                               :metrics/sender-fn :onyx.lifecycle.metrics.timbre/timbre-sender
                               :lifecycle/doc "Instruments a task's metrics to timbre"}
                              {:lifecycle/task :all
                               :lifecycle/calls :onyx-peers.lifecycles.restart-lifecycle/restart-calls}
                              {:lifecycle/task :persist
                               :lifecycle/calls :onyx.plugin.bookkeeper/write-ledger-calls}]
                 :workflow [[:annotate-job :persist]]
                 :task-scheduler :onyx.task-scheduler/balanced}
                (add-read-ledgers :annotate-job batch-size zk-addr ledgers-root-path password ledger-ids))] 
    ;(spit "basic-job.edn" (with-out-str (fipp.edn/pprint job)))
    job))

(defn build-window-state-job 
  [job-num {:keys [batch-size] :as params} zk-addr ledgers-root-path ledger-ids]
  (let [password (.getBytes "INSECUREDEFAULTPASSWORD")
        job (-> {:catalog [{:onyx/name :unwrap
                            :onyx/fn :onyx-peers.functions.functions/unwrap
                            :onyx/type :function
                            :onyx/batch-size batch-size}
                           {:onyx/name :annotate-job
                            :onyx/fn :onyx-peers.functions.functions/annotate-job-num
                            ;:onyx/group-by-key :event-time 
                            ;:onyx/flux-policy :continue
                            :onyx/n-peers 1
                            :jepsen/job-num job-num
                            :onyx/params [:jepsen/job-num]
                            :onyx/type :function
                            :onyx/batch-size batch-size}
                           {:onyx/name :persist
                            :onyx/plugin :onyx.plugin.bookkeeper/write-ledger
                            :onyx/type :output
                            :onyx/medium :bookkeeper
                            :bookkeeper/serializer-fn :onyx.compression.nippy/zookeeper-compress
                            :bookkeeper/password-bytes password
                            :bookkeeper/ensemble-size 3
                            :bookkeeper/quorum-size 3
                            :bookkeeper/zookeeper-addr zk-addr
                            :bookkeeper/digest-type :mac
                            :onyx/batch-size batch-size
                            :onyx/doc "Writes messages to a BookKeeper ledger"}]
                 :windows [{:window/id :collect-segments
                            :window/task :annotate-job
                            :window/type :global
                            :window/aggregation [:onyx.windowing.aggregation/collect-key-value :id]
                            :window/window-key :event-time}]
                 :triggers [{:trigger/window-id :collect-segments
                             :trigger/refinement :onyx.refinements/accumulating
                             :trigger/on :onyx.triggers/segment
                             :trigger/threshold [1 :elements]
                             :trigger/sync :onyx-peers.functions.functions/update-state-log}]
                 :lifecycles [#_{:lifecycle/task :all 
                               :lifecycle/calls :onyx.lifecycle.metrics.metrics/calls
                               :metrics/buffer-capacity 10000
                               :metrics/workflow-name "window-state-job"
                               :metrics/sender-fn :onyx.lifecycle.metrics.timbre/timbre-sender
                               :lifecycle/doc "Instruments a task's metrics to timbre"}
                              {:lifecycle/task :all
                               :lifecycle/calls :onyx-peers.lifecycles.restart-lifecycle/restart-calls}
                              {:lifecycle/task :annotate-job
                               :lifecycle/calls :onyx.plugin.bookkeeper/new-ledger-calls
                               :bookkeeper/serializer-fn :onyx.compression.nippy/zookeeper-compress
                               :bookkeeper/password-bytes password
                               :bookkeeper/ensemble-size 3
                               :bookkeeper/quorum-size 3
                               :bookkeeper/zookeeper-addr zk-addr
                               :bookkeeper/digest-type :mac}
                              {:lifecycle/task :persist
                               :lifecycle/calls :onyx.plugin.bookkeeper/write-ledger-calls}]
                 :workflow [[:unwrap :annotate-job] [:annotate-job :persist]]
                 :task-scheduler :onyx.task-scheduler/balanced}
                (add-read-ledgers :unwrap batch-size zk-addr ledgers-root-path password ledger-ids))] 
    ;(spit "aggregation-job.edn" (with-out-str (fipp.edn/pprint job)))
    job))
