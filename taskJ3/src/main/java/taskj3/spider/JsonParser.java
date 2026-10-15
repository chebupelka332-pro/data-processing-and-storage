package taskj3.spider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonParser() {
    }

    public static Page parsePage(String json) {
        if (json == null) {
            throw new IllegalArgumentException("json is null");
        }
        try {
            Page page = MAPPER.readValue(json, Page.class);
            if (page.getMessage() == null) {
                throw new IllegalArgumentException("missing field \"message\" in: " + json);
            }
            if (page.getSuccessors() == null) {
                throw new IllegalArgumentException("missing field \"successors\" in: " + json);
            }
            return page;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("bad JSON: " + e.getOriginalMessage(), e);
        }
    }
}
