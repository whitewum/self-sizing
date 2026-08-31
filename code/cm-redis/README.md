# KV sidecar source

Standalone Java 17 code for the Redis/Pika IBLT and optimized-Merkle experiments.
It has no third-party runtime dependency. `pom.xml` provides the Maven build;
`scripts/build.sh` provides a direct `javac` build.

Use `config.example.env` only as a template. The exported helper scripts operate
on endpoints supplied by that local configuration and contain no experiment hosts.

Pika is accessed through its Redis-compatible RESP interface; this export does
not contain Pika or Redis server code. Build and prepare a local comparison with:

```bash
scripts/build.sh
cp config.example.env config.env
# Edit config.env, start one sidecar next to each endpoint, then compare:
scripts/start-sidecar.sh a config.env
scripts/start-sidecar.sh b config.env
scripts/compare-existing.sh both config.env --expect-equal
```

The checked-in template uses loopback addresses and empty password fields. Filled
configuration, server datasets, binaries, and operational logs stay outside the
Artifact.
