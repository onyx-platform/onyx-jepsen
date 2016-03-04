# onyx-jepsen

Jepsen testing Onyx. **Work in progress**

## Usage

To run:

1. Set onyx dependency versions for the peers in project.clj.
   Snapshot versions are acceptable, but be sure to lein install them before
   running your tests as you may end up downloading a snapshot jar from
   clojars.

2. If not using Linux, install [Docker Machine](https://docs.docker.com/machine/).

Then create a new "machine":

#### VMware Fusion instructions

Tune disk size, memory size and cpu counts to taste.

```
docker-machine create --driver vmwarefusion --vmwarefusion-disk-size 50000 --vmwarefusion-memory-size 20000 --vmwarefusion-cpu-count "6" jepsen-onyx
```

#### VirtualBox instructions (untested, must support file sharing):
```
docker-machine create --driver virtualbox --virtualbox-disk-size 50000 --virtualbox-memory 20000 jepsen-onyx
```

3. Set docker-machine env:
```
eval "$(docker-machine env jepsen-onyx)"
```

4. Uberjar peers and start docker in docker instance:
```
script/start-containers.sh
```

5. Run from inside docker in docker.
```
script/run-test-start-in-docker.sh TEST_NS
```

Where TEST_NS is currently either `onyx-jepsen.onyx-basic-test` or `onyx-jepsen.onyx-aggregation-test`.

When running a new test, exit the docker instance, and restart the process from
4. The docker containers have everything setup perfectly so that nothing needs
to be downloaded or installed before running a test. The jepsen test does not
clean up after itself so a new container must be started before running a new test.

## Docker Image

onyx-jepsen uses a custom jepsen docker image built specifically to test Onyx.
This includes pre-installed ZooKeeper. See the README in the docker directory
for more details.

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
* [BookKeeper state log / key filter interaction issue #382] (https://github.com/onyx-platform/onyx/issues/382) Known, but theoretical issue, proven to be an issue by jepsen. Resolved.
* [Failed async BookKeeper writes should cause peer to to restart #390] (https://github.com/onyx-platform/onyx/issues/390) Known issue, but shown to be resolved by jepsen.

onyx-bookkeeper plugin:
* [Handle case where peer is restored, but all messages fully acked #4] (https://github.com/onyx-platform/onyx-bookkeeper/issues/4) Unresolved, low priority.
* [Plugin should wait until producer channel has completely finished #3] (https://github.com/onyx-platform/onyx-bookkeeper/issues/3) Unresolved.
* [Plugins using producer threads must be able to pass exceptions back to task #435](https://github.com/onyx-platform/onyx/issues/435) Resolved in onyx-bookkeeper, also fixed in onyx-datomic, onyx-seq, onyx-kafka.

## License

Copyright © 2015 Distributed Masonry LLC

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
