# onyx-jepsen

Jepsen testing Onyx. **Work in progress**

To run:

1. Setup docker machine:
```
DO SOMETHING
```

2. Start docker in docker instance:
```
./scripts/start.sh uberjar
```

3. Run from inside docker in docker.
```
./run thing
```

```
 for i in "n1 n2 n3 n4 n5"; do scp $i:/onyx.log 
```

Discoveries thus far:

* BookKeeper intentionally suicides after a ZooKeeper connection timeout.
Therefore it needs to be monitored so that the server comes back up after a
partition.
* Fix for written but *not* acknowledged?

## Notes

Uses peers with the following configuration to avoid resource starvation running on a single machine:

-D"aeron.client.liveness.timeout=50000000000" -D"aeron.threading.mode=SHARED" -server -XX:+UseG1GC 

## Usage

FIXME

## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
