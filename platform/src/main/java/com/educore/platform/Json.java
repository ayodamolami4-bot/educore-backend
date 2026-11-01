package com.educore.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

@Component
public final class Json {
  private final ObjectMapper mapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Invalid JSON value", ex);
    }
  }

  public <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Invalid JSON document", ex);
    }
  }

  public JsonNode tree(String value) {
    return read(value, JsonNode.class);
  }

  public Object value(String value) {
    return read(value, Object.class);
  }
}
