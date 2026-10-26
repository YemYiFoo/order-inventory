# Testing

Run from the repository root with JDK 21 and Docker's Linux engine running:

```powershell
.\scripts\verify.ps1
# Equivalent:
.\mvnw.cmd -B clean verify
```

The Wrapper runs unit tests, packages the application, then runs integration tests.
Docker is required; database tests are not replaced with H2 or silently skipped.
No sibling project is required. A unit-only `mvnw.cmd test` is useful during edits
but does not verify database or concurrency behaviour.

## Suite structure

| Suite | Purpose |
|---|---|
| [InputTest](../src/test/java/dev/portfolio/orders/InputTest.java) | Strict input/canonicalization and specific duplicate-constraint recognition |
| [ApiIT](../src/test/java/dev/portfolio/orders/ApiIT.java) | Real HTTP against two packaged JVMs sharing disposable MySQL; committed SQL assertions |
| [MySqlSmokeIT](../src/test/java/dev/portfolio/orders/MySqlSmokeIT.java) | MySQL version and InnoDB fixture startup |

ApiIT guards its fixture reset with the database name `oi_acceptance`. It does not
reset the ordinary development database. It starts contenders with latches,
retrieves every Future, bounds waits and stops its processes/containers on cleanup.
Healthy contention scenarios repeat three times; these are correctness checks,
not throughput benchmarks.

## Representative checks

| Behaviour | Test method in ApiIT |
|---|---|
| Valid two-item order, snapshots, totals and committed deductions | `catalogueHealthAndPlacement` |
| Later-item failure rolls back all earlier writes and releases the key | `laterFailureRollsBackEverythingAndKeyCanBeReused` |
| Simultaneous same-key requests across both JVMs create one order | `sameKeyRaceAcrossTwoProcessesThreeRuns` |
| Same key with different contents admits only the winning contents | `differentContentsRaceMatchesWinner` |
| Competing stock requests cannot oversell | `stockAndOverlappingMultiItemRacesThreeRuns` |
| Concurrent cancellation restores once; cancellation/shipment have one legal winner | `concurrentCancelAndShipmentSerializeAcrossProcesses` |
| Lock timeouts undo earlier writes/restoration | `lockTimeoutRollsBackEarlierWorkAndRetrySucceeds`, `cancellationLockTimeoutRollsBackEarlierRestoration` |
| Permissions and owner isolation | `ownershipRolesAndSafeErrors` |
| Response negotiation rejects writes before commit | `unacceptableResponseFormatDoesNotCommitWrites` |
| Replay survives restart; seed migrations do not reset stock | `discardedResponseAndRestartReplayPreserveSeedAndInventory` |
| Stable history and preserved item snapshots | `historyHasStablePagesAndImmutableSnapshots` |

Tests also cover strict/chunked input, encoding headers, framework error statuses,
unrelated uniqueness failures, failed-owner recovery and database outages.
Assertions check stock accounting and totals as well as HTTP responses:

- Initial stock = remaining stock + quantities in PLACED and SHIPPED orders.
- Each order total = sum of its item totals.

The lost-response test deliberately discards a response and checks committed data;
it is not a simulation of every network failure.

## Recorded execution

On 25 October 2026, clean verification passed **2 unit + 23 integration tests**,
with zero failures, errors or skips. Environment: Windows, Temurin 21.0.11+10,
Maven 3.9.16, Docker 29.6.1 and MySQL 8.4.6.

The history-query fixture used 5,000 orders (1,000 for the queried customer).
EXPLAIN selected `ix_orders_history`, type `ref`, estimated rows `1000`, with no
filesort. This records the observed plan, not a percentage speed improvement.

New runs generate results in `target/surefire-reports`, `target/failsafe-reports`
and `target/history-explain.txt`. These generated files are intentionally ignored.
No hosted CI badge, production-load result or cross-platform test claim is made.
