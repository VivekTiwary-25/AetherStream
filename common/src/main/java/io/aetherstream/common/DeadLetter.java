package io.aetherstream.common;

/** Rejected or late record. kind = REJECTED or LATE. eventTime may be null for unparseable records. */
public record DeadLetter(String eventId, String kind, String reason, int partition, long offset,
                         Long eventTime, String raw) {
    public static final String REJECTED = "REJECTED";
    public static final String LATE = "LATE";
}
