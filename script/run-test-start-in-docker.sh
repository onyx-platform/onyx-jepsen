#!/bin/bash
cd /onyx-jepsen
hostname d1 && \
	grep -v "127" /etc/hosts > hosts.tmp && \
	echo "127.0.0.1   localhost d1" >> hosts.tmp && \
	cat hosts.tmp > /etc/hosts && rm -f hosts.tmp && \
	cd /onyx-jepsen/ && \
	lein test |& tee test-output.log

for i in n1 n2 n3 n4 n5; 
do 
	scp $i:/onyx.log store/latest/onyx-$i.log; 
done

cp test-output.log store/latest/
