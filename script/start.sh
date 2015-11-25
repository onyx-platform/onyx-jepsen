#!/bin/bash

set -o errexit
set -o pipefail
set -o nounset
set -o xtrace


if [[ "$1" == "uberjar" ]]; then
	cd onyx-peers && lein with-profile uberjar uberjar && cd ..
fi

docker run -v ~/clojure/onyx-jepsen:/onyx-jepsen -v ~/.m2:/root/.m2 --privileged -t -i tjake/jepsen
