(defproject onyx-jepsen "0.1.0-SNAPSHOT"
  :description "Jepsen testing Onyx"
  :url "github.com/onyx-platform/onyx-jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.onyxplatform/onyx "0.10.0-technical-preview-4"]
                 ;[org.slf4j/slf4j-nop "1.7.21"]
                 [fipp "0.6.4"]
                 [org.onyxplatform/onyx-metrics "0.10.0.0-technical-preview-4" :exclusions [org.onyxplatform/onyx]]
                 [org.onyxplatform/onyx-bookkeeper "0.10.0.0-technical-preview-5" :exclusions [org.onyxplatform/onyx]]
                 [jepsen "0.1.1"]]
  :test-selectors {:jepsen :jepsen
                   :test-jepsen-tests :test-jepsen-tests
                   :all (constantly true)}
  :jvm-opts ^:replace ["-server" "-Xmx6g" "-XX:+UseG1GC"]
  :profiles {:uberjar {:aot [onyx-peers.launcher.launch-prod-peers]}
             :dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]]
                   :source-paths ["env/dev" "src"]}})
