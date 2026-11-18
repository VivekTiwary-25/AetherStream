# T58: Strengthen the integration tests (fixes from review R05)

Lane 8 (tests). Read docs/reviews/R05-tests.md on main and fix findings 1, 2, 4, 5 (finding 3 is covered by a separate
crash-recovery test task). Read the test files and io.aetherstream.reference.* (ReferenceExecutor, KafkaRangeReader,
ResultComparator) on main.

Files (edit only these):
- tests/src/test/java/io/aetherstream/tests/EngineMatchesReferenceIT.java
- tests/src/test/java/io/aetherstream/tests/KafkaOffsetsLookupIT.java
- tests/src/test/java/io/aetherstream/tests/support/WorkloadGenerator.java (only to make sure every one of the 6 partitions
  receives records - e.g. add trucks or keys until all 6 partitions are non-empty under Kafka's default partitioner; assert it)
Do NOT edit support/EngineCluster.java or support/IntegrationInfrastructure.java (another task is editing EngineCluster).

1. Assert the three workers' partition lists are disjoint and their union is exactly {0,1,2,3,4,5}; assert every partition has
   records (end offset > 0).
2. Replace the hand-written replay with io.aetherstream.reference.ReferenceExecutor + ResultComparator; also compare the
   dead-letter multiset (eventId, kind) between the reference result and Postgres exactly (no ">= 1" thresholds).
4. KafkaOffsetsLookupIT: use deltas (offsets read before producing vs after), so it passes regardless of test order and
   without relying on forkEvery.
5. Print the CompareResult JSON and the dead-letter comparison summary to stdout.

Acceptance check: `ci-integration` green on your branch; log shows EngineMatchesReferenceIT PASSED with match=true and
dead letters equal.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, say so in your result.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files".
- The acceptance check runs in GitHub Actions on your task branch. Write code carefully so it compiles first time.
- In your result, list what you changed and anything left undone. If the stricter test reveals a REAL engine bug, do not
  hide it: leave the failing assertion, describe the bug precisely in your result.
