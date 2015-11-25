# onyx-jepsen

Jepsen testing Onyx. **Work in progress**

```
hostname d1 && grep -v "127" /etc/hosts > hosts.tmp && echo "127.0.0.1   localhost d1" >> hosts.tmp && cat hosts.tmp > /etc/hosts && cd /onyx-jepsen/ && lein test
```

Discoveries thus far:

* BookKeeper intentionally suicides after a ZooKeeper connection timeout.
Therefore it needs to be monitored so that the server comes back up after a
partition.
* Fix for written but *not* acknowledged?

## Usage

FIXME

## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
