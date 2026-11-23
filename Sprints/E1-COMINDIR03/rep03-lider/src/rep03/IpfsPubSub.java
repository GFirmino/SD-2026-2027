package rep03;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Adaptador HTTP para o PubSub do Kubo, apenas com a biblioteca padrão. */
public final class IpfsPubSub {
    private final String api;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final Pattern DATA = Pattern.compile("\"data\"\\s*:\\s*\"([^\"]*)\"");

    public IpfsPubSub(String api) {
        this.api = api.replaceAll("/+$", "");
    }

    static String toMultibase(String s) {
        return "u" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static String fromMultibase(String s) {
        if (!s.startsWith("u")) throw new IllegalArgumentException("Esperado multibase base64url");
        return new String(Base64.getUrlDecoder().decode(s.substring(1)), StandardCharsets.UTF_8);
    }

    public void publish(String topic, String message) throws IOException, InterruptedException {
        String boundary = "rep03-" + System.nanoTime();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"msg\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + message + "\r\n--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(api + "/pubsub/pub?arg=" + toMultibase(topic)))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IOException("pub falhou (" + response.statusCode() + "): " + response.body());
    }

    public void subscribe(String topic, Consumer<String> onMessage) throws IOException, InterruptedException {
        // Sem timeout global: esta resposta permanece aberta durante toda a subscrição.
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(api + "/pubsub/sub?arg=" + toMultibase(topic)))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() != 200)
                throw new IOException("sub falhou (" + response.statusCode() + "): "
                        + lines.findFirst().orElse(""));
            System.out.println("SUBSCRITO: " + topic);
            lines.forEach(json -> {
                Matcher matcher = DATA.matcher(json);
                if (matcher.find()) onMessage.accept(fromMultibase(matcher.group(1)));
            });
        }
        throw new IOException("Subscrição terminada: " + topic);
    }
}
