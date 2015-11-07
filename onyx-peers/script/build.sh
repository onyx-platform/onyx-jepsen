#!/bin/bash
set -e
lein clean
lein uberjar
docker build -t onyx-peers:0.1.0 .
