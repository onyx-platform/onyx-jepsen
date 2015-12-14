(ns onyx-jepsen.core
  "Tests for Onyx"
  (:require [clojure.tools.logging :refer :all]
            [knossos.core :as knossos]
            [clojure.core.reducers :as r]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]

            ;; Onyx!
            [onyx.state.log.bookkeeper :as obk]
            [onyx.log.zookeeper]
            [onyx.log.replica :as replica]
            [onyx.compression.nippy :as nippy]
            [onyx.api]
            [onyx.extensions]
            [onyx.system]
            [com.stuartsierra.component :as component]
            [onyx.plugin.bookkeeper]
            [onyx-jepsen.simple-job :as simple-job]
            [onyx-jepsen.gen :as onyx-gen]
            [onyx-jepsen.checker :as onyx-checker]

            [clojure.core.async :as casync :refer [chan >!! <!! close! alts!!]]

            [knossos.op :as op]
            [jepsen.control.util :refer [grepkill]]
            [jepsen [client :as client]
             [core :as jepsen]
             [model :as model]
             [db :as db]
             [tests :as tests]
             [control :as c :refer [|]]
             [checker :as checker]
             [nemesis :as nemesis]
             [generator :as gen]
             [util :refer [timeout meh]]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as net]
            [jepsen.os :as os]
            [jepsen.os.debian :as debian])

  (:import [org.apache.bookkeeper.client LedgerHandle LedgerEntry BookKeeper BookKeeper$DigestType AsyncCallback$AddCallback]
           [knossos.core Model]))

(def version "0.8.3")

(def env-config 
  (-> "onyx-peers/resources/prod-env-config.edn" slurp read-string))

(def peer-config 
  (-> "onyx-peers/resources/prod-peer-config.edn" slurp read-string))

(def peer-setup 
  ; Minimum 5 (input ledgers) + 1 intermediate + 1 output
  {:n-peers 3})

(defn zk-node-ids
  "We number nodes in reverse order so the leader is the first node. Returns a
  map of node names to node ids."
  [test]
  (->> test
       :nodes
       (map-indexed (fn [i node] [node (- (count (:nodes test)) i)]))
       (into {})))

(defn zk-node-id
  [test node]
  (get (zk-node-ids test) node))

(defn zoo-cfg-servers
  "Constructs a zoo.cfg fragment for servers."
  [test]
  (->> (zk-node-ids test)
       (map (fn [[node id]]
              (str "server." id "=" (name node) ":2888:3888")))
       (str/join "\n")))

(def || (c/lit "||"))

(defn setup 
  "Sets up and tears down Onyx"
  [version]
  (reify db/DB
    (setup! [_ test node]

      (c/su
        ;; bookkeeper threw some exceptions because hostname wasn't set correctly?
        (c/exec "hostname" (name node))

        (info node "Uploading peers")
        (c/upload "onyx-peers/target/onyx-peers-0.1.0-SNAPSHOT-standalone.jar" "/onyx-peers.jar")
        (c/upload "script/run-peers.sh" "/run-peers.sh")
        (c/exec :chmod "+x" "/run-peers.sh")

        ; Set up zookeeper
        (c/exec :echo (zk-node-id test node) :> "/etc/zookeeper/conf/myid")
        (c/exec :echo (str (slurp (io/resource "zoo.cfg"))
                           "\n"
                           (zoo-cfg-servers test))
                :> "/etc/zookeeper/conf/zoo.cfg")

        (info node "ZK restarting")
        (c/exec :service :zookeeper :restart)

        (info node "Running peers")
        (c/exec "/run-peers.sh" (:n-peers peer-setup))
        ;; Sleep here shouldn't really be necessary, but clients are connecting
        ;; to ledgers before run-peer's BookKeeper is up
        (c/exec :sleep :60)

        (info node "ZK ready"))

      (info node "set up"))

    (teardown! [_ test node]
      (c/su
        ;; Leave ZooKeeper up for now, may want to inspect it and
        ;; our containers will ensure everything is nice after
        (comment (c/exec :service :zookeeper :stop)
                 (c/exec :rm :-rf
                         (c/lit "/var/lib/zookeeper/version-*")
                         (c/lit "/var/log/zookeeper/*"))
                 (grepkill "java")))
      (info node "tore down"))))

(defn bookkeeper-client []
  (obk/bookkeeper env-config))

(defn read-ledger-entries [ledger-id]
  (let [client (bookkeeper-client)
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

(defn get-written-ledgers [onyx-client job-data onyx-id]
  (onyx.plugin.bookkeeper/read-ledgers-data (:log onyx-client) 
                                            onyx-id 
                                            (:job-id @job-data)
                                            (get-in @job-data [:task-ids :persist :id])))

(defrecord WriteLogClient [client job-data ledger-handles ledger-handle ledger-ids onyx-client]
  client/Client
  (setup! [_ test node]
    (let [client (bookkeeper-client)
          lh (obk/new-ledger client env-config)
          onyx-client (component/start (onyx.system/onyx-client peer-config))]
      (swap! ledger-ids conj (.getId lh))
      (swap! ledger-handles conj lh)
      (WriteLogClient. client lh ledger-ids onyx-client)))

  (invoke! [this test op]
    (let [zk-addr (:zookeeper/address env-config)
          onyx-id (:onyx/id env-config)] 
      (case (:f op)
        :read-peer-log (timeout 4000
                                (assoc op :type :info :value :timed-out)
                                (try
                                  (assoc op 
                                         :type :ok 
                                         :value (read-peer-log (:log onyx-client)))
                                  (catch Throwable t
                                    (assoc op :type :info :value t))))
        :close-ledgers-await-completion (timeout 2000000
                                                 (assoc op :type :info :value :timed-out)
                                                 (try
                                                   (assoc op 
                                                          :type :ok 
                                                          :value (do
                                                                   (mapv (fn [h] 
                                                                           (try (.close h)
                                                                                (catch Throwable t
                                                                                  (info "Error closing handle" t)))) 
                                                                         @ledger-handles)
                                                                   (onyx.api/await-job-completion peer-config (:job-id @job-data))))
                                                   (catch Throwable t
                                                     (assoc op :type :info :value t))))
        :read-ledgers (timeout 1000000
                               (assoc op :type :info :value :timed-out)
                               (try
                                 (assoc op 
                                        :type :ok 
                                        :value (mapv read-ledger-entries (get-written-ledgers onyx-client job-data onyx-id)))
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
                                      :value (->> (simple-job/build-job 1 
                                                                        zk-addr
                                                                        (onyx.log.zookeeper/ledgers-path onyx-id)
                                                                        @ledger-ids)
                                                  (onyx.api/submit-job peer-config)
                                                  (reset! job-data)))
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
    (.close client)))

(defn write-log-client [job-data ledger-handles ledger-ids]
  (->WriteLogClient nil job-data ledger-handles nil ledger-ids nil))

;; Not actually used for anything currently
(defrecord OnyxModel []
  Model
  (step [r op]
    (info "Stepping " op)
    r))

(defn adds
  "Generator that emits :add operations for sequential integers."
  []
  (->> (range)
       (map (fn [x] {:type :invoke, :f :add, :value x}))
       gen/seq))

(defn gc-peer-logs
  "Generator that emits :add operations for sequential integers."
  []
  (->> (range)
       (map (fn [x] {:type :invoke, :f :gc-peer-log}))
       gen/seq))

(defn read-ledgers-gen
  []
  (gen/clients (gen/once {:type :invoke :f :read-ledgers})))

(defn read-peer-log-gen
  []
  (gen/clients (gen/once {:type :invoke :f :read-peer-log})))

(defn close-await-completion-gen
  []
  (gen/clients (gen/once {:type :invoke :f :close-ledgers-await-completion})))

(def os
  (reify os/OS
    (setup! [_ test node]
      (info node "setting up preinstalled debian docker host")
      (debian/setup-hostfile!)
      (meh (net/heal)))

    (teardown! [_ test node])))

(defn basic-test
  "A simple test of Onyx's safety."
  [version]
  (merge tests/noop-test
         {:os os
          :db (setup version)
          :client (write-log-client (atom nil) (atom []) (atom []))
          :model (->OnyxModel) ;; Not actually used for anything currently
          :checker (onyx-checker/->Checker (:n-peers peer-setup))
          :generator (gen/phases
                       (->> (onyx-gen/filter-new identity 
                                                 (onyx-gen/frequency [(adds) 
                                                                      ;(gen/once (gc-peer-logs))
                                                                      (gen/once 
                                                                        (gen/each 
                                                                          {:type :invoke :f :submit-job}))]
                                                                     [0.98
                                                                      ;0.01
                                                                      0.01]))
                            (gen/stagger 1/10)
                            ;(gen/delay 1)
                            (gen/nemesis
                              (gen/seq (cycle
                                         [(gen/sleep 30)
                                          {:type :info :f :start}
                                          (gen/sleep 200)
                                          {:type :info :f :stop}])))
                            (gen/time-limit 4000)) 
                       (close-await-completion-gen)
                       (read-ledgers-gen)
                       (read-peer-log-gen))
          ;:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))
          :nemesis (nemesis/partition-random-halves)
          }))
