#!/bin/bash

set -o errexit
set -o pipefail
set -o nounset
set -o xtrace

TEST=$1

cd /onyx-jepsen

hostname d1 && \
	grep -v "127" /etc/hosts > hosts.tmp && \
	echo "127.0.0.1   localhost d1" >> hosts.tmp && \
	cat hosts.tmp > /etc/hosts && rm -f hosts.tmp && \
	cd /onyx-jepsen/ && \
	lein test :only $TEST |& tee test-output.log || true

cp test-output.log store/latest/
mv metrics.txt store/latest/

for i in n1 n2 n3 n4 n5; 
do 
	mkdir store/latest/$i"_logs"
	scp $i:/onyx.log\* store/latest/$i"_logs"/;
	scp $i:/myrecording.jfr store/latest/recording-$i.jfr; 
	scp $i:/peers-out.log store/latest/peers-out-$i.log; 
done
