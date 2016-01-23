(ns onyx-jepsen.onyx-test
  "Tests for Onyx"
  (:require [clojure.tools.logging :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]

            [onyx-jepsen.simple-job :as simple-job]
            [onyx-jepsen.gen :as onyx-gen]
            [onyx-jepsen.checker :as onyx-checker]
            [onyx-jepsen.onyx-client :as onyx-client]

            [jepsen.model]
            [knossos.op :as op]
            [jepsen.control.util]
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
            [jepsen.os.debian :as debian]))

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
      (info node "tear down: no-op"))))


(def os
  (reify os/OS
    (setup! [_ test node]
      (info node "Setting up preinstalled debian docker host")
      (debian/setup-hostfile!)
      (meh (net/heal)))

    (teardown! [_ test node]))) 

(defn jepsen-test
  "A simple test of Onyx's safety. Supply your own generator for your test"
  [env-config peer-config test-setup name version generator]
  (let [{:keys [n-jobs job-params n-peers time-limit awake-mean stopped-mean]} test-setup]
    (merge tests/noop-test
           {:os os
            :db (setup test-setup version)
            :name name
            :client (onyx-client/write-log-client env-config peer-config (atom {}) (atom []) (atom []))
            :model jepsen.model/noop
            :checker (onyx-checker/->Checker peer-config n-peers n-jobs)
            :generator generator 
            :nemesis (case (:nemesis test-setup) 
                       :bridge-shuffle (nemesis/partitioner (comp nemesis/bridge shuffle))
                       :random-halves (nemesis/partition-random-halves)
                       :na nil)})))
