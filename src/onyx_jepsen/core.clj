(ns onyx-jepsen.core
  "Tests for Onyx"
  (:require [clojure.tools.logging :refer :all]
            [clojure.core.reducers :as r]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [knossos.op :as op]
            [jepsen [client :as client]
             [core :as jepsen]
             [db :as db]
             [tests :as tests]
             [control :as c :refer [|]]
             [checker :as checker]
             [nemesis :as nemesis]
             [generator :as gen]
             [util :refer [timeout meh]]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [jepsen.os.debian :as debian]))

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

(defn setup 
  "Sets up and tears down Onyx"
  [version]
  (reify db/DB
    (setup! [_ test node]
      (c/su
        (info node "Setting up ZK")
        ; Install zookeeper
        (debian/install [:zookeeper :zookeeper-bin :zookeeperd])

        ; Set up zookeeper
        (c/exec :echo (zk-node-id test node) :> "/etc/zookeeper/conf/myid")
        (c/exec :echo (str (slurp (io/resource "zoo.cfg"))
                           "\n"
                           (zoo-cfg-servers test))
                :> "/etc/zookeeper/conf/zoo.cfg")

        ; Restart
        (info node "ZK restarting")
        (c/exec :service :zookeeper :restart)                                                                                                               
        (c/exec "script/run-peers.sh")

        ;(c/exec :nc :-z node :2888)
        (info node "ZK ready"))

      (info node "set up"))

    (teardown! [_ test node]
      (c/su
        (c/exec :service :zookeeper :stop)
        (c/exec :rm :-rf
                (c/lit "/var/lib/zookeeper/version-*")
                (c/lit "/var/log/zookeeper/*"))

        (c/exec :killall :-8 :java))
      (info node "tore down"))))

(defn basic-test
  "A simple test of Onyx's safety."
  [version]
  (merge tests/noop-test
         {:os debian/os
          :db (setup version)}))
