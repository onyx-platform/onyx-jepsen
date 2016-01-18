#!/bin/bash

TEST="onyx-jepsen.onyx-basic-test"

cd /onyx-jepsen
hostname d1 && \
	grep -v "127" /etc/hosts > hosts.tmp && \
	echo "127.0.0.1   localhost d1" >> hosts.tmp && \
	cat hosts.tmp > /etc/hosts && rm -f hosts.tmp && \
	cd /onyx-jepsen/ && \
	lein test :only $TEST |& tee test-output.log

cp test-output.log store/latest/

for i in n1 n2 n3 n4 n5; 
do 
	scp $i:/onyx.log store/latest/onyx-$i.log; 
	scp $i:/myrecording.jfr store/latest/recording-$i.jfr; 
	scp $i:/peers-out.log store/latest/peers-out-$i.log; 
done
