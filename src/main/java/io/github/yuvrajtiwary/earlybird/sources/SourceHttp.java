package io.github.yuvrajtiwary.earlybird.sources;

import io.github.yuvrajtiwary.earlybird.sources.SourceException.Kind;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;

/**
 * The HTTP client every job source shares (spec section 3.6).
 *
 * <ul>
 *   <li>Timeouts: 5 s to connect, 20 s to read. Responses are gzip-compressed where the platform supports it.
 *   <li>At most {@value #MAX_PER_HOST} requests at once per host, so ~150 boards on virtual threads can't
 *       hammer one platform.
 *   <li>Conditional GET: send the last ETag; an unchanged board answers 304 with no body.
 *   <li>5xx and network errors are retried twice within the tick; 404 and 429 are not.
 *   <li>Bodies over {@value #MAX_BODY_BYTES} bytes are rejected, so a broken endpoint can't exhaust memory.
 * </ul>
 */
public final class SourceHttp {

    static final int MAX_PER_HOST = 4;
    static final long MAX_BODY_BYTES = 32L * 1024 * 1024;

    /** Turns a successful response body into a value. The stream is closed by the caller. */
    @FunctionalInterface
    public interface BodyReader<T> {
        T read(InputStream body) throws IOException;
    }

    public sealed interface Result<T> {
        record NotModified<T>() implements Result<T> {}

        record Ok<T>(T body, String etag) implements Result<T> {}
    }

    private final RestClient client;
    private final List<Duration> retryBackoff;
    private final Map<String, Semaphore> permitsByHost = new ConcurrentHashMap<>();

    public SourceHttp(String userAgent) {
        this(userAgent, Duration.ofSeconds(5), Duration.ofSeconds(20), List.of(Duration.ofSeconds(1), Duration.ofSeconds(3)));
    }

    SourceHttp(String userAgent, Duration connectTimeout, Duration readTimeout, List<Duration> retryBackoff) {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        var factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(readTimeout);
        factory.enableCompression(true);
        this.client = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .build();
        this.retryBackoff = List.copyOf(retryBackoff);
    }

    /**
     * {@code EarlyBirdBot/1.0 (+url)} so board operators can see who we are. A localhost base URL tells them
     * nothing, so local runs point at the GitHub repository instead.
     */
    public static String userAgent(String publicBaseUrl) {
        String contact = publicBaseUrl == null || publicBaseUrl.isBlank() || publicBaseUrl.contains("localhost")
                ? "https://github.com/yuvrajtiwary-bitmesraece/earlybird"
                : publicBaseUrl;
        return "EarlyBirdBot/1.0 (+" + contact + ")";
    }

    /**
     * GETs {@code uri}, retrying transient failures.
     *
     * @param etag sent as If-None-Match when not null
     * @throws SourceException when the board can't be read
     */
    public <T> Result<T> get(URI uri, String etag, BodyReader<T> reader) {
        Semaphore permits = permitsByHost.computeIfAbsent(uri.getHost(), h -> new Semaphore(MAX_PER_HOST, true));
        for (int attempt = 0; ; attempt++) {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SourceException(Kind.NETWORK, 0, null, "interrupted", e);
            }
            try {
                return once(uri, etag, reader);
            } catch (SourceException e) {
                if (!e.retryable() || attempt >= retryBackoff.size()) {
                    throw e;
                }
            } finally {
                permits.release(); // never hold the host's slot while sleeping
            }
            sleep(retryBackoff.get(attempt));
        }
    }

    private <T> Result<T> once(URI uri, String etag, BodyReader<T> reader) {
        try {
            return client.get()
                    .uri(uri)
                    .headers(h -> {
                        h.setAccept(List.of(MediaType.APPLICATION_JSON));
                        if (etag != null && !etag.isBlank()) {
                            h.setIfNoneMatch(etag);
                        }
                    })
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.value() == 304) {
                            return new Result.NotModified<T>();
                        }
                        if (status.is2xxSuccessful()) {
                            try (InputStream body = new LimitedInputStream(response.getBody(), MAX_BODY_BYTES)) {
                                return new Result.Ok<>(reader.read(body), response.getHeaders().getETag());
                            }
                        }
                        throw failure(status, response.getHeaders(), uri);
                    }, true);
        } catch (SourceException e) {
            throw e;
        } catch (JacksonException e) {
            throw new SourceException(Kind.BAD_RESPONSE, 200, null, "unreadable JSON from " + uri.getHost(), e);
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof BodyTooLargeException tooLarge) {
                throw new SourceException(Kind.BAD_RESPONSE, 200, null, tooLarge.getMessage(), tooLarge);
            }
            throw new SourceException(Kind.NETWORK, 0, null, "network error calling " + uri.getHost() + ": "
                    + e.getMostSpecificCause().getClass().getSimpleName(), e);
        } catch (RestClientException e) {
            throw new SourceException(Kind.NETWORK, 0, null, "request to " + uri.getHost() + " failed", e);
        }
    }

    private static SourceException failure(HttpStatusCode status, HttpHeaders headers, URI uri) {
        int code = status.value();
        String where = uri.getHost() + uri.getPath();
        if (code == 404) {
            return new SourceException(Kind.NOT_FOUND, code, "404 Not Found: " + where);
        }
        if (code == 429) {
            Duration retryAfter = parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER));
            return new SourceException(Kind.RATE_LIMITED, code, retryAfter, "429 Too Many Requests: " + where, null);
        }
        if (status.is5xxServerError()) {
            return new SourceException(Kind.SERVER_ERROR, code, code + " from " + where);
        }
        return new SourceException(Kind.HTTP_ERROR, code, code + " from " + where);
    }

    /** Retry-After is either seconds ("120") or an HTTP date. */
    static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.strip())));
        } catch (NumberFormatException notSeconds) {
            try {
                ZonedDateTime at = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration d = Duration.between(ZonedDateTime.now(at.getZone()), at);
                return d.isNegative() ? Duration.ZERO : d;
            } catch (RuntimeException unparseable) {
                return null;
            }
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException(Kind.NETWORK, 0, null, "interrupted", e);
        }
    }

    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException(long limit) {
            super("response body larger than " + limit + " bytes");
        }
    }

    /** Fails the read once more than {@code limit} bytes have been consumed. */
    static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                check(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = super.read(buffer, off, len);
            if (n > 0) {
                check(n);
            }
            return n;
        }

        private void check(int n) throws IOException {
            count += n;
            if (count > limit) {
                throw new BodyTooLargeException(limit);
            }
        }
    }
}
