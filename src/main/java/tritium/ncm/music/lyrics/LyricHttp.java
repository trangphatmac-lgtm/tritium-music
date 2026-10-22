package tritium.ncm.music.lyrics;

import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** Anonymous provider requests, separate from the user's NetEase cookies. */
@FunctionalInterface
interface LyricHttp {
    JsonObject request(String url, JsonObject body, Map<String, String> headers, int timeoutMs) throws Exception;

    static LyricHttp create() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(3500)).build();
        return (url, body, headers, timeoutMs) -> {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", "TritiumMusic/1.0.3 (lyrics; Folia port)");
            headers.forEach(request::setHeader);
            if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) return new JsonObject();
            if (response.statusCode() != 200) throw new IOException("Lyric provider HTTP " + response.statusCode());
            if (response.body().length() > 4 * 1024 * 1024) throw new IOException("Lyric response exceeds limit");
            return new JsonParser().parse(response.body()).getAsJsonObject();
        };
    }
    static String query(String url, Map<String, String> params) {
        return url + "?" + params.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
    }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
}
