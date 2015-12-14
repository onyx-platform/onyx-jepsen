#!/bin/bash
cd /onyx-jepsen
hostname d1 && \
	grep -v "127" /etc/hosts > hosts.tmp && \
	echo "127.0.0.1   localhost d1" >> hosts.tmp && \
	cat hosts.tmp > /etc/hosts && \
	cd /onyx-jepsen/ && \
	lein test
