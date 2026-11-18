/**
 * Deterministic checkpoint-range replay and comparison (contracts 02 and 04).
 *
 * <p>Compile a definition with {@link io.aetherstream.compiler.PipelineCompiler}, read the
 * checkpoint-zero-inclusive/latest-checkpoint-exclusive source range with
 * {@link io.aetherstream.reference.KafkaRangeReader}, then pass the records and plan to
 * {@link io.aetherstream.reference.ReferenceExecutor#run}. Replay never flushes open windows;
 * an idle partition retains the initial watermark. Outputs are grouped by sink id, while
 * watermarks are grouped by partition and window node id.
 *
 * <p>{@link io.aetherstream.reference.ResultComparator} compares window primary keys and alert
 * ids. Its result counts include windows and alerts; engineResults counts every supplied row,
 * even rows outside the reference's closed-window frontier. Such window rows are not EXTRA
 * unless their key was accepted in a reference partition and their sink's upstream window
 * watermark has reached their end. At most 50 mismatches are returned; counts and match are
 * computed over all rows. The caller supplies checkpoint id and elapsed duration.
 */
package io.aetherstream.reference;
