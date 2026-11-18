package io.aetherstream.compiler;

import com.fasterxml.jackson.core.type.TypeReference;
import io.aetherstream.common.Json;
import io.aetherstream.common.pipeline.PipelineDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Loads the bundled editor examples from the pipeline resource library. */
public final class PipelineLibrary {
    private static final String RESOURCE_ROOT = "/pipelines/";
    private static final TypeReference<List<Entry>> ENTRIES_TYPE = new TypeReference<>() {};

    private PipelineLibrary() {}

    public static List<Entry> list() {
        try (InputStream input = resource("index.json")) {
            return List.copyOf(Json.MAPPER.readValue(input, ENTRIES_TYPE));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read pipeline library index", exception);
        }
    }

    public static PipelineDefinition load(String id) {
        Entry entry = list().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown pipeline example: " + id));
        try (InputStream input = resource(entry.file())) {
            return Json.MAPPER.readValue(input, PipelineDefinition.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read pipeline example " + id, exception);
        }
    }

    private static InputStream resource(String name) {
        InputStream input = PipelineLibrary.class.getResourceAsStream(RESOURCE_ROOT + name);
        if (input == null) {
            throw new IllegalStateException("Missing pipeline resource " + name);
        }
        return input;
    }

    public record Entry(String id, String name, String description, String file) {}
}
