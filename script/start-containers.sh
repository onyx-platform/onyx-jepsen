#!/bin/bash

set -o errexit
set -o pipefail
set -o nounset
set -o xtrace


if [[ "$1" == "uberjar" ]]; then
	cd onyx-peers && lein clean && lein with-profile uberjar uberjar && cd ..
fi

docker run -h d5 -v /Users/lucas/clojure/onyx-jepsen:/onyx-jepsen -v ~/.m2:/root/.m2 --privileged -t -i lbradstreet/onyx-jepsen
