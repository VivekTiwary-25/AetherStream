package io.aetherstream.api.ws;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FailureLog {
    private static final Logger LOG = LoggerFactory.getLogger(LiveHub.class);
    private final ConcurrentHashMap<String, Long> lastLogged = new ConcurrentHashMap<>();

    void failed(String upstream, Exception error) {
        long now = System.nanoTime();
        lastLogged.compute(upstream, (key, last) -> {
            if (last == null || now - last >= TimeUnit.SECONDS.toNanos(30)) {
                LOG.warn("WebSocket {} unavailable: {}", upstream, error.toString());
                return now;
            }
            return last;
        });
    }
}
