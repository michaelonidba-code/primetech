package dev.primetech.ads.common;

import java.util.List;

/** Body of {@code POST /v1/events}. Players batch events to save radio and connection overhead. */
public record IngestRequest(List<ClientEvent> events) {
}
