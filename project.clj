(defproject onyx-jepsen "0.1.0-SNAPSHOT"
  :description "Jepsen testing Onyx"
  :url "github.com/onyx-platform/onyx-jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.onyxplatform/onyx "0.8.5-SNAPSHOT" :exclusions [org.slf4j/slf4j-nop]]
                 [org.onyxplatform/onyx-bookkeeper "0.8.4.1-SNAPSHOT"]
                 [jepsen "0.0.6"]]
  :test-selectors {:jepsen :jepsen
                   :test-jepsen-tests :test-jepsen-tests
                   :all (constantly true)}
  :jvm-opts ^:replace ["-server" "-Xmx6g"]
  :profiles {:uberjar {:aot [onyx-peers.launcher.aeron-media-driver
                             onyx-peers.launcher.launch-prod-peers]}
             :dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]]
                   :source-paths ["env/dev" "src"]}})
