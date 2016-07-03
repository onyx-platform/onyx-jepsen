#!/bin/bash

set -o errexit
set -o pipefail
set -o nounset
set -o xtrace

BASE_PATH=$(cd "$(dirname "$0")"; pwd)"/.."

# Watch out for space here /Users/lucas/Library/Containers/com.docker.docker/Data/com.docker.driver.amd64-linux

docker rm $(docker ps -a |grep jepsen|cut -f 1 -d ' ') || true

lein clean && lein with-profile uberjar uberjar

# Share the onyx-jepsen code, and m2 directory (so we don't have to redownload jars every time)

docker run -e ONYX_TEST=$1 -h d5 -v $BASE_PATH:/onyx-jepsen -v ~/.m2:/root/.m2 --privileged -t -i lbradstreet/onyx-jepsen
#docker run -e ONYX_TEST=onyx-jepsen.onyx-aggregation-test -h d5 -v $BASE_PATH:/onyx-jepsen -v ~/.m2:/root/.m2 --privileged -t -i lbradstreet/onyx-jepsen
#docker run -e ONYX_TEST=onyx-jepsen.onyx-kill-test -h d5 -v $BASE_PATH:/onyx-jepsen -v ~/.m2:/root/.m2 --privileged -t -i lbradstreet/onyx-jepsen
