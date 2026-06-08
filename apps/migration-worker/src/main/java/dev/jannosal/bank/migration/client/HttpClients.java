package dev.jannosal.bank.migration.client;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Builds {@link RestClient}s with explicit connect/read timeouts. Timeouts are deliberately
 * non-negotiable here: an activity blocked forever on a dead socket would otherwise stall the whole
 * migration (and hang tests). A timed-out call surfaces as an exception that Temporal's activity
 * retry policy handles.
 */
public final class HttpClients {

    private HttpClients() {}

    public static RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public static RestClient restClient(String baseUrl) {
        return restClient(baseUrl, Duration.ofSeconds(5), Duration.ofSeconds(30));
    }
}
