package tech.molecules.structurized.decomposition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * JSON serialization helper for decomposition configurations.
 */
public final class DecompositionJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private DecompositionJson() {}

    public static String writeConfig(DecompositionConfig config) throws JsonProcessingException {
        return MAPPER.writeValueAsString(config);
    }

    public static DecompositionConfig readConfig(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, DecompositionConfig.class);
    }

    public static ObjectMapper mapper() {
        return MAPPER.copy();
    }
}
