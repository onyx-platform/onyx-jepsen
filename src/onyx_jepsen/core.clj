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
            [onyx.compression.nippy :as nippy]                                                                                                             
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
            [jepsen.control.net :as cn]
            [jepsen.os.debian :as debian])

  (:import [org.apache.bookkeeper.client LedgerHandle LedgerEntry BookKeeper BookKeeper$DigestType AsyncCallback$AddCallback]
           [knossos.core Model]))

(def version "0.8.0")

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

(def env-config 
  (-> "onyx-peers/resources/prod-env-config.edn" slurp read-string))

(defn bk-cfg-servers
  [test]
  (str "zkServers=" (clojure.string/join ","
                                         (map (fn [n]
                                                (str (name n) ":2181")) 
                                              (:nodes test)))))

(def bk-ledger-path
  (str "zkLedgersRootPath=/ledgers" #_(:onyx/id env-config)))

(defn setup 
  "Sets up and tears down Onyx"
  [version]
  (reify db/DB
    (setup! [_ test node]

      (c/su
        ;; bookkeeper threw some exceptions because hostname wasn't set correctly?
        (c/exec "hostname" (name node))

        (c/exec :apt-get :update)
        (info node "Uploading peers")
        (c/upload "onyx-peers/target/onyx-peers-0.1.0-SNAPSHOT-standalone.jar" "/onyx-peers.jar")
        (c/upload "script/run-peers.sh" "/run-peers.sh")
        (c/exec :chmod "+x" "/run-peers.sh")

        (comment 
          ;; Disable non-embedded BookKeeper
          (c/upload "bookkeeper-server-4.3.1-bin.tar.gz" "/bookkeeper.tar.gz")
          (c/exec :tar "zxvf" "/bookkeeper.tar.gz")
          (c/exec :mv "bookkeeper-server-4.3.1" "/bookkeeper-server"))

        (c/upload "script/upgrade-java.sh" "/upgrade-java.sh")
        (c/exec :chmod "+x" "/upgrade-java.sh")
        (info node "Upgrading java")
        (c/exec "/upgrade-java.sh")
        (info node "Done upgrading java")

        (info node "Setting up ZK")
        ; Install zookeeper
        (debian/install [:zookeeper :zookeeper-bin :zookeeperd])

        ; Set up zookeeper
        (c/exec :echo (zk-node-id test node) :> "/etc/zookeeper/conf/myid")
        (c/exec :echo (str (slurp (io/resource "zoo.cfg"))
                           "\n"
                           (zoo-cfg-servers test))
                :> "/etc/zookeeper/conf/zoo.cfg")

        (info node "ZK restarting")
        (c/exec :service :zookeeper :restart)
        
        (comment 
          ;; Disable non-embedded BookKeeper
          (c/exec :echo (str (slurp (io/resource "bk_server.conf"))
                                    "\n"
                                    (bk-cfg-servers test)
                                    "\n"
                                    bk-ledger-path)
                         :> "/bookkeeper-server/conf/bk_server.conf")                                                                                                               
                 (info node "starting bookkeeper")
                 (c/exec :echo "N" | "/bookkeeper-server/bin/bookkeeper" "shell" "metaformat" || "true")
                 (c/exec "/bookkeeper-server/bin/bookkeeper-daemon.sh" "start" "bookie"))


        (info node "Running peers")
        (c/exec "/run-peers.sh")

        (info node "ZK ready"))

      (info node "set up"))

    (teardown! [_ test node]
      (c/su
        (comment (c/exec :service :zookeeper :stop)
                 (c/exec :rm :-rf
                         (c/lit "/var/lib/zookeeper/version-*")
                         (c/lit "/var/log/zookeeper/*"))
                 (grepkill "java")))
      (info node "tore down"))))

(def ledger-ids (atom []))

(defn bookkeeper-client []
  #_(obk/bookkeeper (:zookeeper/address env-config) "/ledgers" 60000 30000)
  (obk/bookkeeper env-config))

(defn read-ledger-entries [ledger-id]
  (let [client (bookkeeper-client)
        pwd (obk/password env-config)]
    (let [ledger-handle (obk/open-ledger client ledger-id obk/digest-type pwd)
          results (try 
                    (let [last-confirmed (.getLastAddConfirmed ledger-handle)]
                      (if-not (neg? last-confirmed)
                        (loop [results [] 
                               entries (.readEntries ledger-handle 0 last-confirmed)
                               element ^LedgerEntry (.nextElement entries)] 
                          (let [new-results (conj results (nippy/decompress (.getEntry element)))] 
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

(defrecord WriteLogClient [client ledger-handle]
  client/Client
  (setup! [_ test node]
    (let [client (bookkeeper-client)
          lh (obk/new-ledger client env-config)]
      (swap! ledger-ids conj (.getId lh))
      (WriteLogClient. client lh)))

  (invoke! [this test op]
    (case (:f op)
      :read-ledger (timeout 500000
                            (assoc op :type :info :value :timed-out)
                            (try
                              (assoc op 
                                     :type :ok 
                                     :value (mapv read-ledger-entries @ledger-ids))
                              (catch Throwable t
                                (assoc op :type :info :value t))))
      :add (timeout 5000 
                    (assoc op :type :info :value :timed-out)
                    (try
                      (do 
                        (info "Adding entry" (:value op))
                        (.addEntry ledger-handle (nippy/compress (:value op)))
                        (info "Done adding entry" (:value op))
                        (assoc op :type :ok :ledger-id (.getId ledger-handle)))
                      (catch Throwable t
                        (assoc op :type :info :value (.getMessage t)))))))

  (teardown! [_ test]
    (.close client)))

(defn write-log-client []
  (->WriteLogClient nil nil))

(defn adds
  "Generator that emits :add operations for sequential integers."
  []
  (->> (range)
       (map (fn [x] {:type :invoke, :f :add, :value x}))
       gen/seq))

(defrecord Checker []
  checker/Checker
  (check [checker test model history]
    (info "Checked " test model history)
    (let [ledger-reads (first (filter (fn [action]
                                        (and (= (:f action) :read-ledger)
                                             (= (:type action) :ok)))
                                      history))
          results (map :results (:value ledger-reads))
          all-in-order? (= results (map sort results))
          successfully-added (filter (fn [action]
                                       (and (= (:f action) :add)
                                            (= (:type action) :ok)))
                                     history)
          added-values (sort (map :value successfully-added))
          read-values (sort (reduce into [] results))
          all-written-read? (= added-values read-values)] 
      {:valid? (and all-in-order? all-written-read?)
       :in-order? all-in-order?
       :added added-values
       :read-values read-values
       :all-written-read? all-written-read?})))

(defrecord OnyxModel []
  Model
  (step [r op]
    (info "Stepping " op)
    r))

(defn read-ledger
  []
  (gen/clients (gen/once {:type :invoke :f :read-ledger})))

(defn basic-test
  "A simple test of Onyx's safety."
  [version]
  (merge tests/noop-test
         {:os debian/os
          :db (setup version)
          :client (write-log-client)
          :model (->OnyxModel)
          :checker (->Checker)
          :generator (gen/phases
                       (->> (adds)
                            (gen/stagger 1/10)
                            (gen/delay 1)
                            (gen/nemesis
                              (gen/seq (cycle
                                         [(gen/sleep 30)
                                          {:type :info :f :start}
                                          (gen/sleep 200)
                                          {:type :info :f :stop}])))
                            (gen/time-limit 800)) 
                       (read-ledger))
          ;:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))
          :nemesis (nemesis/partition-random-halves)}))
