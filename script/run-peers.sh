#!/usr/bin/env bash

mount -t tmpfs -o remount,rw,nosuid,nodev,noexec,relatime,size=1024M tmpfs /dev/shm

nohup java -cp onyx-peers.jar onyx_peers.launcher.launch_prod_peers 4 > peers-out.log 2>&1 < /dev/null &

if [ $? -eq 0 ]
then
  exit 0
else
  exit 1
fi
