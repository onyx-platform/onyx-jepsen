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
  [test-setup version]
  (reify db/DB
    (setup! [_ test node]

      (c/su
        ;; bookkeeper threw some exceptions because hostname wasn't set correctly?
        (c/exec "hostname" (name node))

        (info node "Uploading peers")
        (c/upload "target/onyx-jepsen-0.1.0-SNAPSHOT-standalone.jar" "/onyx-peers.jar")
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
        (c/exec "/run-peers.sh" (:n-peers test-setup))
        ;; Sleep here shouldn't really be necessary, but clients are connecting
        ;; to ledgers before run-peer's BookKeeper is up
        (c/exec :sleep :60)

        (info node "ZK ready"))

      (info node "set up"))

    (teardown! [_ test node]
      ;; Leave ZooKeeper up for now, may want to inspect it and
      ;; docker ensures everything is still in a good state on the next run
      (info node "tore down no-o no-opp"))))

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

(defn get-written-ledgers [onyx-client job-data onyx-id]
  (onyx.plugin.bookkeeper/read-ledgers-data (:log onyx-client) 
                                            onyx-id 
                                            (:job-id job-data)
                                            (get-in job-data [:task-ids :persist :id])))


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

;; TODO, merge jobs-data, ledger-handles, and ledger-ids atoms into one big atom map
(defrecord WriteLogClient [env-config peer-config client jobs-data ledger-handles ledger-handle ledger-ids onyx-client]
  client/Client
  (setup! [this test node]
    (let [client (bookkeeper-client env-config)
          lh (obk/new-ledger client env-config)
          onyx-client (component/start (onyx.system/onyx-client peer-config))]
      (swap! ledger-ids conj (.getId lh))
      (swap! ledger-handles conj lh)
      (assoc this :ledger-handle lh :onyx-client onyx-client)))

  (invoke! [this test op]
    (let [zk-addr (:zookeeper/address env-config)
          onyx-id (:onyx/id env-config)] 
      (case (:f op)
        :read-peer-log (timeout 1000000
                                (assoc op :type :info :value :timed-out)
                                (try
                                  (assoc op 
                                         :type :ok 
                                         :value (onyx-checker/read-peer-log (:log onyx-client) (or (:timeout-ms op) 20000)))
                                  (catch Throwable t
                                    (assoc op :type :info :value t))))
        :close-ledgers-await-completion (timeout 2000000
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
                                        :value (into {} 
                                                     (map (fn [[job-num job-data]] 
                                                            (vector job-num 
                                                                    (mapv (partial read-ledger-entries env-config) 
                                                                          (get-written-ledgers onyx-client job-data onyx-id))))
                                                          @jobs-data)))
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
    (.close client)))

(defn write-log-client [env-config peer-config jobs-data ledger-handles ledger-ids]
  (map->WriteLogClient {:env-config env-config :peer-config peer-config
                        :jobs-data jobs-data :ledger-handles ledger-handles :ledger-ids ledger-ids}))

;; Doesn't do anything yet
(defrecord OnyxModel []
  Model
  (step [r op]
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

(defn submit-job-gen [n-jobs job-params]
  (->> (range n-jobs)
       (map (fn [n] 
              {:type :invoke 
               :f :submit-job 
               :job-num n
               :n-jobs n-jobs
               :params job-params}))
       gen/seq))

(defn start-stop-nemesis-seq [awake-mean stopped-mean]
  (gen/seq 
    (mapcat (fn [_] 
              [(gen/sleep (rand-int (* 2 stopped-mean)))
               {:type :info :f :start}
               (gen/sleep (rand-int (* 2 awake-mean)))
               {:type :info :f :stop}]) 
            (range))))

(def os
  (reify os/OS
    (setup! [_ test node]
      (info node "Setting up preinstalled debian docker host")
      (debian/setup-hostfile!)
      (meh (net/heal)))

    (teardown! [_ test node]))) 

(defn basic-test
  "A simple test of Onyx's safety."
  [env-config peer-config test-setup version]
  (let [{:keys [n-jobs job-params n-peers time-limit awake-mean stopped-mean]} test-setup]
    (merge tests/noop-test
           {:os os
            :db (setup test-setup version)
            :name "onyx-basic"
            :client (write-log-client env-config peer-config (atom {}) (atom []) (atom []))
            :model (->OnyxModel) ;; Currently not actually used for anything
            :checker (onyx-checker/->Checker peer-config n-peers n-jobs)
            :generator (gen/phases
                         (->> (onyx-gen/filter-new identity 
                                                   (onyx-gen/frequency [(adds) 
                                                                        (submit-job-gen n-jobs job-params)
                                                                        ;(gen/once (gc-peer-logs))
                                                                        ]
                                                                       [0.99
                                                                        ;0.01
                                                                        0.01]))
                              (gen/stagger 1/10)
                              ;(gen/delay 1)
                              (gen/nemesis (start-stop-nemesis-seq awake-mean stopped-mean))
                              (gen/time-limit time-limit)) 

                         ;; Bring everything back at the end
                         ;; This way we can test that the peers came back up
                         (gen/nemesis (gen/once {:type :info :f :stop}))
                         ;; Sleep for a while to give peers a chance to come back up
                         ;; Should be enough time that curator backoff * max-retries is covered
                         (gen/sleep 120)

                         (close-await-completion-gen)
                         (read-ledgers-gen)
                         (read-peer-log-gen))
            :nemesis (case (:nemesis test-setup) 
                       :bridge-shuffle (nemesis/partitioner (comp nemesis/bridge shuffle))
                       :random-halves (nemesis/partition-random-halves)
                       :na nil)})))
