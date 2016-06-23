(defproject onyx-jepsen "0.1.0-SNAPSHOT"
  :description "Jepsen testing Onyx"
  :url "github.com/onyx-platform/onyx-jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.onyxplatform/onyx "0.9.7-SNAPSHOT"]
                 ;[org.onyxplatform/onyx "0.9.6" ;:exclusions [org.slf4j/slf4j-nop]]
                 [fipp "0.6.4"]
                 [org.onyxplatform/onyx-metrics "0.9.6.0"]
                 [org.onyxplatform/onyx-bookkeeper "0.9.6.0"]
                 [jepsen "0.0.9"]]
  ;;; NOTE, don't swallow in BK
  :test-selectors {:jepsen :jepsen
                   :test-jepsen-tests :test-jepsen-tests
                   :all (constantly true)}
  :jvm-opts ^:replace ["-server" "-Xmx6g" "-XX:+UseG1GC"]
  :profiles {:uberjar {:aot [onyx-peers.launcher.aeron-media-driver
                             onyx-peers.launcher.launch-prod-peers]}
             :dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]]
                   :source-paths ["env/dev" "src"]}})
