#!/usr/bin/env bash
set -o errexit
set -o nounset
set -o xtrace

echo "Setting shared memory for Aeron"
#mount -t tmpfs -o remount,rw,nosuid,nodev,noexec,relatime,size=256M tmpfs /dev/shm
lein run -m onyx-peers.launcher.launch-prod-peers "someid" 4
