package eu.wohlben.qits.platformdocs;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The one thing this service talks to: qits-artifacts' docs repository, over qits-net.
 *
 * <p>Hand-rolled {@code java.net.http} rather than a REST client, the rule every outbound call on
 * this platform follows: a client library is a reflection surface to register and an image to
 * carry, for four requests whose shapes are known.
 *
 * <p><b>The {@link HttpClient} is an instance field, not a static one.</b> That is not style — a
 * static client is constructed during native-image build and an {@code HttpClientFacade} in the
 * image heap fails the build. Four sibling services carry this same note; the rule travels with the
 * client rather than with the package.
 *
 * <p><b>Nothing here caches.</b> A published version is immutable and carries an immutable cache
 * header, so the browser and any proxy in front of this process do the caching that matters, and a
 * cache here would only add a place for {@code latest} to be stale. The one read that could
 * legitimately be cached is the version list, and it is deliberately not: it is what makes {@code
 * latest} mean the newest version *now*, which is the whole reason this service can hold no state.
 */
@ApplicationScoped
public class DocsUpstream {

  private static final Logger LOG = Logger.getLogger(DocsUpstream.class);

  /**
   * qits-artifacts' docs repository root, including the repository segment — the same value CI
   * injects into a publishing step as {@code $QITS_DOCS_URL}, so a deployment configures one
   * address and the publisher and the reader agree by construction.
   */
  @ConfigProperty(name = "qits.platform-docs.artifacts-url")
  String artifactsUrl;

  /**
   * Connect and whole-exchange deadlines. A file is streamed rather than buffered, so the read
   * deadline bounds the <em>response head</em> and not the transfer — a ten-megabyte bundle over
   * qits-net must not be a timeout, and an unreachable qits-artifacts must not be a hung request.
   */
  @ConfigProperty(name = "qits.platform-docs.connect-timeout", defaultValue = "PT2S")
  Duration connectTimeout;

  @ConfigProperty(name = "qits.platform-docs.request-timeout", defaultValue = "PT30S")
  Duration requestTimeout;

  private HttpClient http;

  @PostConstruct
  void open() {
    http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
  }

  /** One upstream response, still streaming. The caller owns {@link #body} and must close it. */
  record Fetched(
      int status, String contentType, String contentLength, String etag, InputStream body)
      implements AutoCloseable {

    @Override
    public void close() {
      try {
        body.close();
      } catch (IOException ignored) {
        // The response is being abandoned; a failure to drain it is not the reader's problem.
      }
    }
  }

  /**
   * Every published version of one site, newest first — qits-artifacts' own ordering, taken rather
   * than recomputed.
   *
   * <p>That ordering is the whole of how {@code latest} works here, and taking it is deliberate:
   * "which version is newest" is a fact about the store's rows, and a second opinion formed by
   * parsing version strings in this process is a second opinion that can disagree.
   *
   * @return the versions, or an empty list when the site has none
   * @throws DocsUpstreamException the store could not be asked
   */
  List<String> versions(String site) {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(uri(site)).GET(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 404) {
      return List.of();
    }
    if (response.statusCode() != 200) {
      throw new DocsUpstreamException(
          "qits-artifacts answered HTTP " + response.statusCode() + " for the versions of " + site);
    }
    List<String> versions = new ArrayList<>();
    try {
      JsonArray listed = new JsonObject(response.body()).getJsonArray("versions", new JsonArray());
      for (int i = 0; i < listed.size(); i++) {
        versions.add(listed.getJsonObject(i).getString("version"));
      }
    } catch (RuntimeException malformed) {
      throw new DocsUpstreamException(
          "qits-artifacts answered something that is not a version list: "
              + malformed.getMessage());
    }
    return List.copyOf(versions);
  }

  /**
   * One file of one published version, as a live stream.
   *
   * <p>The status is returned rather than translated, because the only two that reach a reader are
   * 200 and 404 and they mean here exactly what they mean there.
   *
   * @throws DocsUpstreamException the store could not be asked
   */
  Fetched fetch(String site, String version, String path) {
    HttpResponse<InputStream> response =
        send(
            HttpRequest.newBuilder(uri(site + "/-/" + version + "/" + path)).GET(),
            HttpResponse.BodyHandlers.ofInputStream());
    return new Fetched(
        response.statusCode(),
        header(response, "content-type"),
        header(response, "content-length"),
        header(response, "etag"),
        response.body());
  }

  private static String header(HttpResponse<?> response, String name) {
    return response.headers().firstValue(name).orElse(null);
  }

  private URI uri(String suffix) {
    // Built by concatenation rather than through URI.resolve: a site name carries slashes and often
    // a leading @, and every convenience API in sight would either decode or re-encode it. The
    // segments reaching here have already been matched against DocsPaths' character classes, so
    // there is nothing in them a URI could legitimately need to escape.
    String base =
        artifactsUrl.endsWith("/")
            ? artifactsUrl.substring(0, artifactsUrl.length() - 1)
            : artifactsUrl;
    return URI.create(base + "/" + suffix);
  }

  private <T> HttpResponse<T> send(
      HttpRequest.Builder builder, HttpResponse.BodyHandler<T> bodyHandler) {
    try {
      return http.send(builder.timeout(requestTimeout).build(), bodyHandler);
    } catch (java.io.InterruptedIOException interrupted) {
      Thread.currentThread().interrupt();
      throw new DocsUpstreamException("the request to qits-artifacts was interrupted");
    } catch (IOException unreachable) {
      LOG.debugf(unreachable, "docs upstream unreachable at %s", artifactsUrl);
      throw new DocsUpstreamException(
          "qits-artifacts could not be reached: " + unreachable.getMessage());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new DocsUpstreamException("the request to qits-artifacts was interrupted");
    }
  }

  /**
   * The store could not be asked — distinct from "the store says no such thing", which is a 404 and
   * a normal answer. A reader gets 502 for this: it says the failure is behind this process rather
   * than in the URL they typed, which is the single most useful thing to tell whoever is debugging.
   */
  static class DocsUpstreamException extends RuntimeException {
    DocsUpstreamException(String message) {
      super(message);
    }
  }
}
