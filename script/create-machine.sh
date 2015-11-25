docker-machine create --driver vmwarefusion --vmwarefusion-disk-size 50000 --vmwarefusion-memory-size 20000 --vmwarefusion-cpu-count "5" jepsen-vmware
eval "$(docker-machine env jepsen-vmware)"
