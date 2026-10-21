package dev.primetech.ads.common.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonHttpTest {
    @Test
    void anyOriginWhenUnsetOrStar() {
        assertThat(JsonHttp.allowedOrigin(null, "https://a.example")).isEqualTo("*");
        assertThat(JsonHttp.allowedOrigin(" * ", "https://a.example")).isEqualTo("*");
        assertThat(JsonHttp.allowedOrigin("", null)).isEqualTo("*");
    }

    @Test
    void echoesAListedOriginOnly() {
        String allowed = "https://1world1dollar.com, https://www.1world1dollar.com";
        assertThat(JsonHttp.allowedOrigin(allowed, "https://www.1world1dollar.com")).isEqualTo("https://www.1world1dollar.com");
        assertThat(JsonHttp.allowedOrigin(allowed, "https://1world1dollar.com")).isEqualTo("https://1world1dollar.com");
        assertThat(JsonHttp.allowedOrigin(allowed, "https://evil.example")).isEqualTo("https://1world1dollar.com");
        assertThat(JsonHttp.allowedOrigin(allowed, null)).isEqualTo("https://1world1dollar.com");
    }
}
