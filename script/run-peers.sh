#!/usr/bin/env bash

### Script for Jepsen to run inside nodes to startup peers

mount -t tmpfs -o remount,rw,nosuid,nodev,noexec,relatime,size=2048M tmpfs /dev/shm

BIND_ADDR=$(ifconfig eth0 | grep "inet addr:" | cut -d : -f 2 | cut -d " " -f 1)
N_PEERS=$1

## TODO REMOVE LIVENESS
nohup java -D"aeron.client.liveness.timeout=50000000000"  \
	   -D"aeron.term.buffer.length=4194304" \
	    -D"aeron.threading.mode=SHARED" -server -XX:+UseG1GC \
                        -XX:+UnlockCommercialFeatures -XX:+FlightRecorder -XX:+UnlockDiagnosticVMOptions \
			-XX:StartFlightRecording="duration=1080s,filename=myrecording.jfr" \
	-cp onyx-peers.jar onyx_peers.launcher.launch_prod_peers $N_PEERS $BIND_ADDR > peers-out.log 2>&1 < /dev/null &

if [ $? -eq 0 ]
then
  exit 0
else
  exit 1
fi
