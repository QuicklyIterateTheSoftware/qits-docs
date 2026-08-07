package eu.wohlben.qits.platformdocs;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The reading surface, at {@code /platform-docs}.
 *
 * <p>Four routes and one idea: turn a URL a person can hold in their head into a version-addressed
 * one, then stream the file qits-artifacts has under it.
 *
 * <p><b>Everything this service adds beyond the passthrough is a redirect</b>, and that is a
 * decision rather than an economy. A documentation bundle refers to its own assets
 * <em>relatively</em> — Storybook emits {@code ./assets/…} — so what a browser has to end up on is
 * a directory URL, or every asset resolves one level too high and 404s. Serving content at {@code
 * …/2026.807.0} instead of redirecting to {@code …/2026.807.0/} would produce a page that loads and
 * is then blank, which is the failure mode hardest to read. So {@code /platform-docs/<site>} and
 * {@code …/-/<version>} both answer 302 and nothing else.
 *
 * <p><b>{@code latest} is a query, not a pointer.</b> There is no alias table here and no state at
 * all: the newest version is the first element of qits-artifacts' own version list, read on every
 * request. That is what lets a redeploy of this service lose nothing and a published version become
 * the latest the instant it lands.
 *
 * <p>The redirect to a version is <b>302, deliberately, not 301</b>. What {@code
 * /platform-docs/<site>/} means changes with every release, so a permanent redirect would pin a
 * reader's browser to whatever version was newest the first time they visited, for as long as their
 * cache lives.
 *
 * <p><b>Raw Vert.x routes, and responses built as bytes or headers.</b> Nothing here is serialised
 * by binding, so this stack adds zero native-image configuration — the rule qits-artifacts' four
 * wire packages follow.
 */
@ApplicationScoped
public class DocsRoutes {

  private static final Logger LOG = Logger.getLogger(DocsRoutes.class);

  /** What a version root serves. The bundle's own entry point, and every generator emits it. */
  private static final String INDEX = "index.html";

  @Inject DocsUpstream upstream;

  void init(@Observes Router router) {
    // The machine surface first. It does not have to be — DocsPaths reserves `api/` and `q/` out of
    // the site grammar, so nothing below can claim these however they are ordered — but a reader
    // arriving at this method should see the narrow routes before the ones that look like a
    // catch-all, and the two guards costing nothing is the point of having both.
    router.get(DocsPaths.BASE + "/api/sites").blockingHandler(guarded("sites", this::sites));

    // Then longest path first. Also disjoint by construction: a bare `-` is not a name segment, so
    // no site path can be read as a version path and no version root as a file.
    router.getWithRegex(DocsPaths.FILE).blockingHandler(guarded("file", this::file));
    router.getWithRegex(DocsPaths.VERSION_INDEX).blockingHandler(guarded("index", this::index));
    router
        .getWithRegex(DocsPaths.VERSION_ROOT)
        .blockingHandler(guarded("version root", this::toDirectory));
    router.getWithRegex(DocsPaths.SITE_LATEST).blockingHandler(guarded("latest", this::latest));
    router.getWithRegex(DocsPaths.SITE).blockingHandler(guarded("latest", this::latest));
  }

  // --- reading ----------------------------------------------------------------------------------

  /** {@code GET /platform-docs/<site>[/]} — 302 to the newest version's directory. */
  private void latest(RoutingContext rc) {
    String site = rc.pathParam("name");
    List<String> versions = upstream.versions(site);
    if (versions.isEmpty()) {
      DocsErrors.send(rc, 404, "nothing is published under '" + site + "'");
      return;
    }
    redirect(rc, DocsPaths.BASE + "/" + site + "/-/" + versions.getFirst() + "/");
  }

  /**
   * {@code GET /platform-docs/<site>/-/<version>} — 302 to the same path with a trailing slash.
   *
   * <p>The whole of this route is that slash, and it is load-bearing: see the class javadoc. The
   * version is not checked to exist first, because the redirect target answers that itself and
   * asking twice would cost a reader a round trip to be told the same thing.
   */
  private void toDirectory(RoutingContext rc) {
    redirect(rc, rc.normalizedPath() + "/");
  }

  /** {@code GET /platform-docs/<site>/-/<version>/} — the bundle's own entry point. */
  private void index(RoutingContext rc) {
    stream(rc, rc.pathParam("name"), rc.pathParam("version"), INDEX);
  }

  /** {@code GET /platform-docs/<site>/-/<version>/<path>} — one file. */
  private void file(RoutingContext rc) {
    stream(rc, rc.pathParam("name"), rc.pathParam("version"), rc.pathParam("path"));
  }

  /**
   * Streams one file through, headers and all.
   *
   * <p>Blocking, on a worker thread, and copied through a buffer rather than proxied: a docs file
   * is a few hundred kilobytes and the whole store is one network hop away, so the simple shape is
   * the right one here — and unlike qits-gateway, which must never buffer because it carries SSE
   * and git smart-HTTP, nothing on this route is long-lived or interactive.
   *
   * <p>The upstream's {@code ETag} and content type are passed through unchanged. Its {@code
   * Cache-Control} is <b>not</b> — {@link #cacheControlFor} restates it here, because what a
   * version root may claim differs from what a hashed asset may claim and only this service knows
   * which URL the reader is on.
   */
  private void stream(RoutingContext rc, String site, String version, String path) {
    try (DocsUpstream.Fetched fetched = upstream.fetch(site, version, path)) {
      if (fetched.status() == 404) {
        DocsErrors.send(rc, 404, "no such file in " + site + "@" + version + ": " + path);
        return;
      }
      if (fetched.status() != 200) {
        DocsErrors.send(
            rc, 502, "qits-artifacts answered HTTP " + fetched.status() + " for " + path);
        return;
      }
      HttpServerResponse response = rc.response();
      if (fetched.contentType() != null) {
        response.putHeader(HttpHeaders.CONTENT_TYPE, fetched.contentType());
      }
      if (fetched.contentLength() != null) {
        response.putHeader(HttpHeaders.CONTENT_LENGTH, fetched.contentLength());
      }
      if (fetched.etag() != null) {
        response.putHeader(HttpHeaders.ETAG, fetched.etag());
      }
      response.putHeader(HttpHeaders.CACHE_CONTROL, cacheControlFor(path));
      copy(fetched.body(), response);
    }
  }

  /**
   * How long a reader may hold on to this file.
   *
   * <p>Two answers, and the split is the reason this is not simply passed through from upstream.
   * Every URL here is version-addressed, so its <em>bytes</em> can never change — but {@code
   * index.html} is what a {@code latest} redirect lands on, and a browser that cached it for a year
   * would keep rendering an old release's entry point after following a redirect to a new one. So
   * the entry point revalidates and everything the bundle references, whose names carry a content
   * hash, is immutable.
   */
  private static String cacheControlFor(String path) {
    return path.equals(INDEX) || path.endsWith("/" + INDEX)
        ? "public, max-age=0, must-revalidate"
        : "public, max-age=31536000, immutable";
  }

  /** {@code GET /platform-docs/api/sites} — what is published, for the reader's own index. */
  private void sites(RoutingContext rc) {
    // Deliberately thin for now: the catalog belongs to qits-artifacts, which is the only thing
    // that
    // knows what rows exist, and this route is the seam the SPA reads. It is a placeholder only in
    // the sense that qits-artifacts has no list-every-site endpoint yet — when it does, this
    // proxies
    // it and gains no opinion of its own.
    DocsErrors.send(rc, 501, "the site catalog is not implemented yet");
  }

  // --- plumbing ---------------------------------------------------------------------------------

  private static void redirect(RoutingContext rc, String location) {
    rc.response()
        .setStatusCode(302)
        .putHeader(HttpHeaders.LOCATION, location)
        // A redirect whose target changes with every release must not be cached, or a reader's
        // browser pins itself to whichever version was newest when they first visited.
        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        .end();
  }

  private static void copy(InputStream body, HttpServerResponse response) {
    byte[] buffer = new byte[8192];
    try {
      int read;
      while ((read = body.read(buffer)) != -1) {
        response.write(io.vertx.core.buffer.Buffer.buffer(java.util.Arrays.copyOf(buffer, read)));
      }
      response.end();
    } catch (IOException aborted) {
      // A reader that hit stop, or an upstream that hung up mid-file. Neither is this service's
      // error and neither can be answered — the head is already on the wire.
      LOG.debugf(aborted, "docs: send aborted after %d bytes", response.bytesWritten());
      if (!response.ended()) {
        response.close();
      }
    }
  }

  /**
   * Wraps a handler so every throwable becomes the plain-text envelope rather than {@code
   * QuarkusErrorHandler}'s HTML page.
   *
   * <p>That matters more here than anywhere: the client is a browser assembling a website, and an
   * HTML body is exactly what it will render in place of the page that was asked for.
   */
  private Handler<RoutingContext> guarded(String what, Handler<RoutingContext> handler) {
    return rc -> {
      try {
        handler.handle(rc);
      } catch (Throwable thrown) {
        DocsErrors.fail(rc, what, thrown);
      }
    };
  }
}
