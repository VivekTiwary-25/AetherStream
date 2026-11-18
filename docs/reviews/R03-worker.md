VERDICT: FAIL

1. `engine/src/main/java/io/aetherstream/engine/worker/TaskRuntime.java:58,159-162` — Contract 03 §1 requires bounded runtime buffers. `pendingBarriers` is an unbounded `ConcurrentLinkedQueue`; checkpoint requests can accumulate while the source thread is blocked or delayed before draining them. **MAJOR** — bound/coalesce pending checkpoint requests (or reject excess) so queued control items cannot grow without limit.
