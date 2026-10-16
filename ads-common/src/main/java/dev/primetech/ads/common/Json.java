package dev.primetech.ads.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One shared, thread-safe mapper so every service reads and writes events the same way. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            // Clients may be newer than the gateway; extra fields must not fail a whole batch.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // An unknown event type becomes null and is rejected per event by the validator.
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);

    private Json() {
    }
}
