import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class Healthcheck {
  public static void main(String[] args) {
    try {
      String port = System.getenv().getOrDefault("SERVER_PORT", "8080");
      HttpRequest request = HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + Integer.parseInt(port) + "/actuator/health"))
          .timeout(Duration.ofSeconds(2)).GET().build();
      int status = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
          .send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      System.exit(status == 200 ? 0 : 1);
    } catch (Exception exception) {
      System.exit(1);
    }
  }
}
