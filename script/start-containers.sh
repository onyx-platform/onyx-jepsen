#!/bin/bash

set -o errexit
set -o pipefail
set -o nounset
set -o xtrace

BASE_PATH=$(cd "$(dirname "$0")"; pwd)"/.."

docker rm $(docker ps -a |grep jepsen|cut -f 1 -d ' ') || true

lein clean && lein with-profile uberjar uberjar

docker run -e ONYX_TEST=$1 -e AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY -h d5 -v $BASE_PATH:/onyx-jepsen -v ~/.m2:/root/.m2 --privileged -t -i lbradstreet/onyx-jepsen /bin/bash
