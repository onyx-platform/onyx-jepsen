# onyx-jepsen

Jepsen testing Onyx. **Work in progress**

## Usage

To run:

1. If not using Linux, setup docker machine:

Tune disk size, memory size and cpu counts to taste.

VMware fusion instructions:
```
docker-machine create --driver vmwarefusion --vmwarefusion-disk-size 50000 --vmwarefusion-memory-size 20000 --vmwarefusion-cpu-count "6" jepsen-onyx
```

Virtualbox instructions (untested, must support file sharing):
```
docker-machine create --driver virtualbox --virtualbox-disk-size 50000 --virtualbox-memory 20000 jepsen-onyx
```

2. Set docker-machine env:
```
eval "$(docker-machine env jepsen-onyx)"
```

2. Uberjar peers and start docker in docker instance:
```
scripts/start-containers.sh uberjar
```

3. Run from inside docker in docker.
```
script/run-test-start-in-docker.sh
```

## Notes

Uses peers with the following configuration to avoid resource starvation running on a single machine:

-D"aeron.client.liveness.timeout=50000000000" -D"aeron.threading.mode=SHARED" -server -XX:+UseG1GC 

## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
