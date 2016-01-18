(ns onyx-peers.jobs.basic-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [clojure.test :refer [deftest is]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.test-helper :refer [load-config with-test-env]]
            [onyx-jepsen.simple-job :as simple-job]
            [taoensso.timbre :refer [fatal info]]
            [onyx.api]))

(deftest basic-test
  (let [id (java.util.UUID/randomUUID)
        config (load-config)
        env-config (assoc (:env-config config) :onyx/id id)
        peer-config (assoc (:peer-config config) :onyx/id id)
        batch-size 20]

    (with-test-env [test-env [15 env-config peer-config]]




      )))
