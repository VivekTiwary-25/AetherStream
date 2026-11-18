package io.aetherstream.reference;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaRangeReaderTest {
    private static final String TOPIC = "requested-topic";

    @Test void readsPartitionsIndependentlyAndFiltersExclusiveEnds() {
        TopicPartition first = partition(0);
        TopicPartition second = partition(1);
        ScriptedConsumer consumer = new ScriptedConsumer(Map.of(first, 0L, second, 0L),
                Map.of(first, 20L, second, 20L));
        consumer.polls.add(budget -> {
            consumer.positions.putAll(Map.of(first, 8L, second, 11L));
            return new ConsumerRecords<>(Map.of(
                    first, List.of(record(0, 2), record(0, 4), record(0, 5), record(0, 7)),
                    second, List.of(record(1, 10))));
        });
        consumer.polls.add(budget -> {
            assertThat(consumer.paused).containsExactly(first);
            consumer.positions.put(second, 15L);
            return new ConsumerRecords<>(Map.of(second,
                    List.of(record(1, 12), record(1, 13), record(1, 14))));
        });

        Map<Integer, List<RawRecord>> result = reader(consumer).read("broker.example:19092", TOPIC,
                Map.of(0, 2L, 1, 10L, 2, 4L), Map.of(0, 5L, 1, 13L, 2, 4L));

        assertThat(result).containsOnlyKeys(0, 1, 2);
        assertThat(result.get(0)).containsExactly(new RawRecord(0, 2, "value-2"), new RawRecord(0, 4, "value-4"));
        assertThat(result.get(1)).containsExactly(new RawRecord(1, 10, "value-10"), new RawRecord(1, 12, "value-12"));
        assertThat(result.get(2)).isEmpty();
        assertThat(consumer.assigned).containsExactlyInAnyOrder(first, second);
        assertThat(consumer.seeks).containsExactlyInAnyOrderEntriesOf(Map.of(first, 2L, second, 10L));
        assertThat(consumer.positionCalls).containsExactly(first, second, second);
        assertThat(consumer.paused).containsExactlyInAnyOrder(first, second);
        assertThat(consumer.closeBudget).isGreaterThanOrEqualTo(Duration.ZERO).isLessThanOrEqualTo(consumer.budgets.getLast());
        assertThat(consumer.closeCalls).isEqualTo(1);
    }

    @Test void usesPositionToCompleteRangesContainingOnlySkippedOffsets() {
        TopicPartition partition = partition(0);
        ScriptedConsumer consumer = new ScriptedConsumer(Map.of(partition, 0L), Map.of(partition, 10L));
        consumer.polls.add(budget -> {
            consumer.positions.put(partition, 5L);
            return new ConsumerRecords<>(Map.of());
        });

        assertThat(reader(consumer).read("broker:9092", TOPIC, Map.of(0, 2L), Map.of(0, 5L)))
                .containsEntry(0, List.of());
        assertThat(consumer.positionCalls).containsExactly(partition);
        assertThat(consumer.paused).containsExactly(partition);
        assertThat(consumer.closeCalls).isEqualTo(1);
    }

    @Test void neverCreatesConsumerForEmptyRangesOrNoPartitions() {
        KafkaRangeReader reader = new KafkaRangeReader(properties -> {
            throw new AssertionError("Empty ranges must not connect to Kafka");
        }, Duration.ofSeconds(30));

        assertThat(reader.read("unused:9092", TOPIC, Map.of(), Map.of())).isEmpty();
        assertThat(reader.read("unused:9092", TOPIC, Map.of(0, 8L, 1, 0L), Map.of(0, 8L, 1, 0L)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(0, List.of(), 1, List.of()));
    }

    @Test void rejectsInvalidArgumentsBeforeCreatingConsumer() {
        KafkaRangeReader reader = new KafkaRangeReader(properties -> {
            throw new AssertionError("Invalid arguments must not create a consumer");
        }, Duration.ofSeconds(30));

        assertThatThrownBy(() -> reader.read(" ", TOPIC, Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read("broker:9092", " ", Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read("broker:9092", TOPIC, Map.of(0, 0L), Map.of(1, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read("broker:9092", TOPIC, Map.of(-1, 0L), Map.of(-1, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read("broker:9092", TOPIC, Map.of(0, -1L), Map.of(0, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read("broker:9092", TOPIC, Map.of(0, 2L), Map.of(0, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        Map<Integer, Long> nullOffset = new HashMap<>();
        nullOffset.put(0, null);
        assertThatThrownBy(() -> reader.read("broker:9092", TOPIC, nullOffset, Map.of(0, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
        Map<Integer, Long> nullPartition = new HashMap<>();
        nullPartition.put(null, 0L);
        assertThatThrownBy(() -> reader.read("broker:9092", TOPIC, nullPartition, nullPartition))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read("broker:9092", TOPIC, null, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KafkaRangeReader(properties -> null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void refusesRangesLostToRetentionOrBeyondCurrentEndAndCloses() {
        TopicPartition partition = partition(0);
        for (Map<Integer, Long> from : List.of(Map.of(0, 2L), Map.of(0, 4L))) {
            ScriptedConsumer consumer = new ScriptedConsumer(Map.of(partition, 3L), Map.of(partition, 10L));
            long to = from.get(0) == 2L ? 5L : 11L;

            assertThatThrownBy(() -> reader(consumer).read("broker:9092", TOPIC, from, Map.of(0, to)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable");
            assertThat(consumer.positionCalls).isEmpty();
            assertThat(consumer.closeCalls).isEqualTo(1);
        }
    }

    @Test void enforcesOverallDeadlineWithoutReturningPartialPollAndClosesWithZeroBudget() {
        TopicPartition partition = partition(0);
        ScriptedConsumer consumer = new ScriptedConsumer(Map.of(partition, 0L), Map.of(partition, 10L));
        consumer.polls.add(budget -> {
            long started = System.nanoTime();
            while (System.nanoTime() - started <= budget.toNanos()) {
                Thread.onSpinWait();
            }
            consumer.positions.put(partition, 1L);
            return new ConsumerRecords<>(Map.of(partition, List.of(record(0, 0))));
        });
        KafkaRangeReader reader = new KafkaRangeReader(consumer::create, Duration.ofMillis(100));

        assertThatThrownBy(() -> reader.read("broker:9092", TOPIC, Map.of(0, 0L), Map.of(0, 3L)))
                .isInstanceOf(TimeoutException.class).hasMessageContaining("complete Kafka ranges");
        assertThat(consumer.closeCalls).isEqualTo(1);
        assertThat(consumer.closeBudget).isEqualTo(Duration.ZERO);
    }

    @Test void closesOnConsumerTimeoutAndPreservesFailureIfCloseAlsoFails() {
        TopicPartition partition = partition(0);
        ScriptedConsumer consumer = new ScriptedConsumer(Map.of(partition, 0L), Map.of(partition, 10L));
        TimeoutException timeout = new TimeoutException("position unavailable");
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        consumer.positionFailure = timeout;
        consumer.closeFailure = closeFailure;
        consumer.polls.add(budget -> new ConsumerRecords<>(Map.of(partition, List.of(record(0, 0)))));

        assertThatThrownBy(() -> reader(consumer).read("broker:9092", TOPIC, Map.of(0, 0L), Map.of(0, 3L)))
                .isSameAs(timeout).hasSuppressedException(closeFailure);
        assertThat(consumer.closeCalls).isEqualTo(1);
    }

    private static KafkaRangeReader reader(ScriptedConsumer consumer) {
        return new KafkaRangeReader(consumer::create, Duration.ofSeconds(30));
    }

    private static TopicPartition partition(int partition) {
        return new TopicPartition(TOPIC, partition);
    }

    private static ConsumerRecord<String, String> record(int partition, long offset) {
        return new ConsumerRecord<>(TOPIC, partition, offset, "key", "value-" + offset);
    }

    /** Deliberately implements only range-read operations: commits/subscriptions fail the test. */
    private static final class ScriptedConsumer implements InvocationHandler {
        private final Map<TopicPartition, Long> beginnings;
        private final Map<TopicPartition, Long> ends;
        private final Map<TopicPartition, Long> positions = new HashMap<>();
        private final Map<TopicPartition, Long> seeks = new HashMap<>();
        private final Set<TopicPartition> assigned = new HashSet<>();
        private final Set<TopicPartition> paused = new HashSet<>();
        private final List<TopicPartition> positionCalls = new ArrayList<>();
        private final List<Duration> budgets = new ArrayList<>();
        private final Deque<Function<Duration, ConsumerRecords<String, String>>> polls = new ArrayDeque<>();
        private Map<String, Object> properties;
        private Duration closeBudget;
        private int closeCalls;
        private RuntimeException positionFailure;
        private RuntimeException closeFailure;

        private ScriptedConsumer(Map<TopicPartition, Long> beginnings, Map<TopicPartition, Long> ends) {
            this.beginnings = beginnings;
            this.ends = ends;
        }

        @SuppressWarnings("unchecked")
        private Consumer<String, String> create(Map<String, Object> properties) {
            this.properties = properties;
            return (Consumer<String, String>) Proxy.newProxyInstance(Consumer.class.getClassLoader(),
                    new Class<?>[]{Consumer.class}, this);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "assign" -> assigned.addAll((Set<TopicPartition>) args[0]);
                case "beginningOffsets", "endOffsets" -> {
                    assertThat(args).hasSize(2);
                    budgets.add((Duration) args[1]);
                    assertThat((Set<TopicPartition>) args[0]).containsExactlyInAnyOrderElementsOf(assigned);
                    return method.getName().equals("beginningOffsets") ? beginnings : ends;
                }
                case "seek" -> {
                    assertThat(args).hasSize(2);
                    seeks.put((TopicPartition) args[0], (Long) args[1]);
                    positions.put((TopicPartition) args[0], (Long) args[1]);
                }
                case "poll" -> {
                    Duration budget = (Duration) args[0];
                    budgets.add(budget);
                    return polls.removeFirst().apply(budget);
                }
                case "position" -> {
                    assertThat(args).hasSize(2);
                    TopicPartition partition = (TopicPartition) args[0];
                    assertThat(paused).doesNotContain(partition);
                    positionCalls.add(partition);
                    budgets.add((Duration) args[1]);
                    if (positionFailure != null) {
                        throw positionFailure;
                    }
                    return positions.get(partition);
                }
                case "pause" -> paused.addAll((Set<TopicPartition>) args[0]);
                case "close" -> {
                    assertThat(args).hasSize(1);
                    closeBudget = (Duration) args[0];
                    closeCalls++;
                    if (closeFailure != null) {
                        throw closeFailure;
                    }
                }
                default -> throw new AssertionError("Unexpected Consumer operation: " + method.getName());
            }
            return null;
        }
    }
}
