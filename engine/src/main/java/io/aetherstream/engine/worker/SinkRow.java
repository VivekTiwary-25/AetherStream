package io.aetherstream.engine.worker;

import io.aetherstream.compiler.ops.ListCollector;

/** One operator output together with the source position of the input record that produced it. */
public record SinkRow(int partition, long offset, ListCollector.Output output) {}
