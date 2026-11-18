package io.aetherstream.engine.worker;

import java.util.List;

/** Output side of the worker (contract 03 section 5). */
public interface ResultSink {
    /**
     * Writes all rows in ONE transaction, fenced on the generation: if the execution's current generation differs
     * from {@code generation}, nothing is written and false is returned. An empty list only performs the fence check.
     *
     * @throws Exception on a transient failure (nothing committed); the caller retries the same batch
     */
    boolean write(String executionId, long generation, List<SinkRow> rows) throws Exception;
}
