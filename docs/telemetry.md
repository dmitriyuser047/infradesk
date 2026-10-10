# Server and container telemetry

Every SSH synchronization collects, besides the inventory, a telemetry sample of the server and its
Docker containers. Nothing is installed on the server: the backend sends one fixed, read-only
Python program (`src/main/resources/ssh/telemetry.py`) and parses its single output line.

## What is collected

Server-wide readings are stored in the node status and recorded as metric observations, so they
have history, rollups and monitor rules like CPU and memory:

| Metric | How |
| --- | --- |
| `DISK_USAGE_PERCENT`, `DISK_FREE_BYTES`, `INODE_USAGE_PERCENT` | worst writable local filesystem (`statvfs`); bind mounts counted once, network filesystems skipped |
| `SWAP_USAGE_PERCENT`, `SWAP_USED_BYTES` | `/proc/meminfo`, only when swap exists |
| `LOAD_AVERAGE_1/5/15`, `LOAD_PER_CORE` | `/proc/loadavg`, CPU count |
| `CPU_IOWAIT_PERCENT` | `/proc/stat` over one second |
| `DISK_READ/WRITE_BYTES_PER_SECOND` (sum), `DISK_BUSY_PERCENT`, `DISK_LATENCY_MILLISECONDS` (worst) | `/proc/diskstats` of whole devices over one second |
| `NETWORK_RECEIVE/TRANSMIT_BYTES_PER_SECOND`, `NETWORK_ERRORS/DROPS_PER_SECOND` (sum) | `/proc/net/dev` without loopback over one second; a counter reset drops the interface for that sample |
| `SERVICE_AVAILABLE` (0/1, worst), `SERVICE_RESPONSE_MILLISECONDS`, `TLS_DAYS_REMAINING` | HTTP `HEAD /` to ports 80 and 443 over loopback, with the SSH host as Host/SNI; HTTPS verifies the certificate, its expiry is read separately |
| `DISK_HEALTHY` (0/1), `DISK_TEMPERATURE_CELSIUS`, `DISK_WEAR_PERCENT`, `DISK_MEDIA_ERRORS` | existing SMART/NVMe data from `smartctl -a -j` when available and readable |

Per device (filesystem, disk, interface, local service, drive) the latest readings are kept with the
resource for the interface; they have no history.

Containers get `CPU_USAGE_PERCENT` (normalized to host capacity) and `MEMORY_USAGE_PERCENT` from one
`docker stats --no-stream` call for all containers, and `CONTAINER_RESTART_COUNT` and
`CONTAINER_HEALTHY` (0/1, only with a healthcheck) from `docker inspect` in batches of 100. Docker
connections read the same values from the Engine API with at most four containers in parallel.
Container readings are recorded as observations of the container, and monitor rules apply to
containers as well as servers.

## Safety and failure

- The program text is fixed and sent base64-encoded; the only argument, the SSH host used as
  Host/SNI, is base64-encoded too. No remote string reaches a shell.
- It reads `/proc`, `/sys` and filesystem statistics, runs `smartctl`, `docker` and `openssl` only
  with fixed arguments, probes only `127.0.0.1`, never follows redirects, never starts SMART
  self-tests and never elevates privileges.
- Every external tool has a timeout and the whole program a 12-second budget. A missing tool,
  permission or reading leaves that value out; nothing absent is reported as zero. Without
  `python3` the inventory works as before, without telemetry.
- Readings are validated when decoded (non-negative, percentages at most 100); an invalid sample
  is dropped as a whole, the inventory is kept.
- The program targets the `python3` of the server and stays compatible with Python 3.6. Its
  contracts are tested with the standard library in CI (`scripts/test-ssh-telemetry.py`).

## In the interface

A resource's overview shows the latest sample: resource-wide values, then each device group. The
Monitoring tab draws CPU, memory and one chosen metric for the last hour, 24 hours, 7 days or
30 days at the resolution the period calls for (see
[metric retention](metrics-maintenance-acknowledgement.md)). Values are shown in their unit; byte
counts and rates in binary multiples. Notifications name each metric and show its unit.
