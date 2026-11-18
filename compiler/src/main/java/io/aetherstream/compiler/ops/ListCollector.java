package io.aetherstream.compiler.ops;

import io.aetherstream.common.Alert;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.WindowResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One ordered stream, preserving interleaving between all output kinds. */
public final class ListCollector implements Collector {
    public sealed interface Output permits WindowOutput, AlertOutput, DeadLetterOutput {}
    public record WindowOutput(String sinkNodeId, WindowResult result) implements Output {}
    public record AlertOutput(String sinkNodeId, Alert alert) implements Output {}
    public record DeadLetterOutput(DeadLetter deadLetter) implements Output {}

    private final List<Output> outputs = new ArrayList<>();
    private final List<Output> view = Collections.unmodifiableList(outputs);

    public List<Output> outputs() { return view; }

    @Override public void windowResult(String sinkNodeId, WindowResult r) {
        outputs.add(new WindowOutput(sinkNodeId, r));
    }
    @Override public void alert(String sinkNodeId, Alert a) {
        outputs.add(new AlertOutput(sinkNodeId, a));
    }
    @Override public void deadLetter(DeadLetter d) {
        outputs.add(new DeadLetterOutput(d));
    }
}
