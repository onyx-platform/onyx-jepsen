# onyx-jepsen

Jepsen testing Onyx. **Work in progress**

## Usage

To run:

1. Set onyx dependency versions for the peers in onyx-peers/project.clj.
   Snapshot versions are acceptable, but be sure to lein install them before
   running your tests as you may end up downloading a snapshot jar from
   clojars.

2. If not using Linux, setup docker machine:

Tune disk size, memory size and cpu counts to taste.

VMware fusion instructions:
```
docker-machine create --driver vmwarefusion --vmwarefusion-disk-size 50000 --vmwarefusion-memory-size 20000 --vmwarefusion-cpu-count "6" jepsen-onyx
```

Virtualbox instructions (untested, must support file sharing):
```
docker-machine create --driver virtualbox --virtualbox-disk-size 50000 --virtualbox-memory 20000 jepsen-onyx
```

3. Set docker-machine env:
```
eval "$(docker-machine env jepsen-onyx)"
```

4. Uberjar peers and start docker in docker instance:
```
scripts/start-containers.sh uberjar
```

5. Run from inside docker in docker.
```
script/run-test-start-in-docker.sh
```

When running a new test, exit the docker instance, and restart the process from
4. The docker containers have everything setup perfectly so that nothing needs
to be downloaded or installed before running a test. The jepsen test does not
clean up after itself so a new container must be started before running a new test.

## Notes

Uses peers with the following configuration to avoid resource starvation running on a single machine:

-D"aeron.client.liveness.timeout=50000000000" -D"aeron.threading.mode=SHARED" -server -XX:+UseG1GC 

See script/run-peers.sh for settings.

## Jepsen Memorial Box

A memorial to those bugs destroyed by Jepsen, or at large, so far:

BookKeeper:

* [Document that BookKeeper servers will shutdown on losing quorum](https://issues.apache.org/jira/browse/BOOKKEEPER-882) Unresolved.

onyx:

* [Peer join race condition #453](https://github.com/onyx-platform/onyx/issues/453) Resolved.
* [Peers that crash on component/start will not reboot #437] (https://github.com/onyx-platform/onyx/issues/437) Resolved. 
* [Ensure peer restarts after ZooKeeper connection loss/errors #423] (https://github.com/onyx-platform/onyx/issues/423) Resolved.

onyx-bookkeeper plugin:
* [Handle case where peer is restored, but all messages fully acked #4] (https://github.com/onyx-platform/onyx-bookkeeper/issues/4) Unresolved, low priority.
* [Plugin should wait until producer channel has completely finished #3] (https://github.com/onyx-platform/onyx-bookkeeper/issues/3) Unresolved.
* [Plugins using producer threads must be able to pass exceptions back to task #435](https://github.com/onyx-platform/onyx/issues/435) Resolved in BookKeeper, likely present in onyx-datomic, onyx-seq, onyx-kafka.

## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
