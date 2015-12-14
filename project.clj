(defproject onyx-jepsen "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.onyxplatform/onyx "0.8.4-SNAPSHOT" :exclusions [org.slf4j/slf4j-nop]]
                 [org.onyxplatform/onyx-bookkeeper "0.8.4.0-SNAPSHOT"]
                 [jepsen "0.0.6"]])
