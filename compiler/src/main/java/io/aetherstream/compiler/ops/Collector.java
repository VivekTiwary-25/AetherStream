package io.aetherstream.compiler.ops;

import io.aetherstream.common.Alert;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.WindowResult;

/** Receives outputs in processing order, addressed to their sink nodes. */
public interface Collector {
    void windowResult(String sinkNodeId, WindowResult r);
    void alert(String sinkNodeId, Alert a);
    void deadLetter(DeadLetter d);
}
