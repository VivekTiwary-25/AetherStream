package io.aetherstream.simulator;

import java.util.Map;
import java.util.Objects;

public record SimulatorConfig(
        long seed,
        double timeScale,
        int initialTrucks,
        double malformedRate,
        boolean stuckTruckDemo,
        String kafkaBootstrap,
        String kafkaTopic) {

    public SimulatorConfig {
        if (!Double.isFinite(timeScale) || timeScale <= 0) {
            throw new IllegalArgumentException("SIM_TIME_SCALE must be finite and positive");
        }
        if (initialTrucks < 0) {
            throw new IllegalArgumentException("SIM_INITIAL_TRUCKS must be nonnegative");
        }
        if (!Double.isFinite(malformedRate) || malformedRate < 0 || malformedRate > 1) {
            throw new IllegalArgumentException("SIM_MALFORMED_RATE must be between 0 and 1");
        }
        if (kafkaBootstrap == null || kafkaBootstrap.isBlank()) {
            throw new IllegalArgumentException("KAFKA_BOOTSTRAP must not be blank");
        }
        if (kafkaTopic == null || kafkaTopic.isBlank()) {
            throw new IllegalArgumentException("KAFKA_TOPIC must not be blank");
        }
    }

    public SimulatorConfig() {
        this(42L, 10.0, 8, 0.0025, true, "kafka:9092", "truck-events");
    }

    public static SimulatorConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static SimulatorConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return new SimulatorConfig(
                parseLong(environment, "SIM_SEED", "42"),
                parseDouble(environment, "SIM_TIME_SCALE", "10"),
                parseInt(environment, "SIM_INITIAL_TRUCKS", "8"),
                parseDouble(environment, "SIM_MALFORMED_RATE", "0.0025"),
                parseBoolean(environment, "SIM_STUCK_TRUCK_DEMO", "true"),
                environment.getOrDefault("KAFKA_BOOTSTRAP", "kafka:9092").trim(),
                environment.getOrDefault("KAFKA_TOPIC", "truck-events").trim());
    }

    private static long parseLong(Map<String, String> environment, String name, String fallback) {
        try {
            return Long.parseLong(environment.getOrDefault(name, fallback).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a long integer", e);
        }
    }

    private static int parseInt(Map<String, String> environment, String name, String fallback) {
        try {
            return Integer.parseInt(environment.getOrDefault(name, fallback).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static double parseDouble(Map<String, String> environment, String name, String fallback) {
        try {
            return Double.parseDouble(environment.getOrDefault(name, fallback).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number", e);
        }
    }

    private static boolean parseBoolean(Map<String, String> environment, String name, String fallback) {
        String value = environment.getOrDefault(name, fallback).trim();
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }
}
