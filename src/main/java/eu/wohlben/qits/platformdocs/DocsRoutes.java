package eu.wohlben.qits.platformdocs;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
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

  /**
   * Where these routes sit relative to Quinoa's two, and it is the whole reason the client renders.
   *
   * <p>Measured order (Quinoa 2.8.2, Quarkus 3.34.6): Quinoa registers its <b>static resources</b>
   * at 1060 and its <b>SPA fallback</b> near 40 000. These routes must sit strictly between them,
   * which is qits-gateway's arrangement and for the same two reasons:
   *
   * <ul>
   *   <li><b>Below 1060 the client stops loading.</b> Its bundle is {@code main-<hash>.js} — one
   *       segment, alphanumeric, a perfectly good site name — so {@code SITE} claims it, asks the
   *       store for the versions of a site called {@code main-O4M5TZHF.js}, and answers 404. The
   *       index renders and every asset it asks for is gone. Measured, not reasoned about.
   *   <li><b>At or past 40 000 the reading routes stop working.</b> The SPA fallback would answer
   *       every unmatched path with {@code index.html} first, so a bundle file would come back as
   *       the client's own shell at 200.
   * </ul>
   *
   * <p>Both Quinoa numbers are read off the jar and are not API — re-check them when the Quinoa pin
   * moves.
   */
  private static final int ROUTE_ORDER = 20_000;

  void init(@Observes Router router) {
    // The machine surface first. It does not have to be — DocsPaths reserves `api/` and `q/` out of
    // the site grammar, so nothing below can claim these however they are ordered — but a reader
    // arriving at this method should see the narrow routes before the ones that look like a
    // catch-all, and the two guards costing nothing is the point of having both.
    router
        .get(DocsPaths.BASE + "/api/sites")
        .order(ROUTE_ORDER)
        .blockingHandler(guarded("sites", this::sites));
    router
        .get(DocsPaths.BASE + "/api/versions")
        .blockingHandler(guarded("versions", this::apiVersions));

    // Then longest path first. Also disjoint by construction: a bare `-` is not a name segment, so
    // no site path can be read as a version path and no version root as a file.
    router
        .getWithRegex(DocsPaths.FILE)
        .order(ROUTE_ORDER)
        .blockingHandler(guarded("file", this::file));
    router
        .getWithRegex(DocsPaths.VERSION_INDEX)
        .order(ROUTE_ORDER)
        .blockingHandler(guarded("index", this::index));
    router
        .getWithRegex(DocsPaths.VERSION_ROOT)
        .order(ROUTE_ORDER)
        .blockingHandler(guarded("version root", this::toDirectory));
    router
        .getWithRegex(DocsPaths.SITE_LATEST)
        .order(ROUTE_ORDER)
        .blockingHandler(guarded("latest", this::latest));
    router
        .getWithRegex(DocsPaths.SITE)
        .order(ROUTE_ORDER)
        .blockingHandler(guarded("latest", this::latest));
  }

  // --- reading ----------------------------------------------------------------------------------

  /**
   * {@code GET /platform-docs/<site>[/]} — 302 to the <b>reader</b>, not to the bundle.
   *
   * <p><b>This is the human entry point, so it must land somewhere with the version picker on
   * it.</b> It used to redirect straight to the newest bundle directory, which served a
   * full-viewport Storybook with no rail — so opening a doc took the version picker away, and the
   * one URL a person would paste or bookmark was the one that could not switch version. The bundle
   * keeps its own address ({@code …/-/<version>/…}); this spelling means "read this", and reading
   * happens in the shell.
   *
   * <p>No version is resolved here any more. The reader fetches the list it needs for the picker
   * anyway, so resolving the newest here as well would be the same question asked twice, one round
   * trip earlier, and a chance for the two answers to differ.
   */
  private void latest(RoutingContext rc) {
    String site = rc.pathParam("name");
    // A SINGLE segment beginning with @ is a scope, not a site — /platform-docs/@qits is the index
    // of what @qits publishes, and the client owns that page. Falling through hands it to Quinoa's
    // SPA route, which is registered after these; answering 404 here would make every scope page a
    // dead link, and answering the SPA here would put a second renderer in this service.
    if (site.startsWith("@") && site.indexOf('/') < 0) {
      rc.next();
      return;
    }
    // Still checked here, because a 404 for a site that does not exist is worth answering before a
    // page load rather than inside one — the reader would have to render a shell to say the same.
    if (upstream.versions(site).isEmpty()) {
      DocsErrors.send(rc, 404, "nothing is published under '" + site + "'");
      return;
    }
    redirect(rc, DocsPaths.BASE + "/read/" + site);
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

  /**
   * {@code GET /platform-docs/api/sites} — the catalog, grouped by scope.
   *
   * <p><b>The grouping happens here and nowhere below.</b> The store answers a flat list, because a
   * scope lives in a site's name and deciding what it groups under is a reading choice — so it is
   * this service's, and a different reader is free to make a different one.
   *
   * <p>A name with no scope goes under the empty group rather than being hidden or invented a home:
   * a service documenting itself as {@code qits-platform-docs} has no scope and must still be
   * findable. The client renders that group without a heading.
   */
  private void sites(RoutingContext rc) {
    // LinkedHashMap, because the store answers ordered by name and the scopes should come out in
    // that order too — a catalog that reshuffles between reloads is a catalog nobody trusts.
    Map<String, JsonArray> byScope = new LinkedHashMap<>();
    for (DocsUpstream.CatalogEntry entry : upstream.catalog()) {
      String name = entry.name();
      int slash = name.startsWith("@") ? name.indexOf('/') : -1;
      String scope = slash > 0 ? name.substring(0, slash) : "";
      byScope
          .computeIfAbsent(scope, s -> new JsonArray())
          .add(
              new JsonObject()
                  .put("name", name)
                  // What the client puts in the URL after the scope. For an unscoped name it is the
                  // whole name, which is why this is computed rather than assumed to be a suffix.
                  .put("shortName", slash > 0 ? name.substring(slash + 1) : name)
                  .put("versionCount", entry.versionCount())
                  .put("latestVersion", entry.latestVersion()));
    }
    JsonArray scopes = new JsonArray();
    byScope.forEach(
        (scope, docs) -> scopes.add(new JsonObject().put("scope", scope).put("docs", docs)));
    respond(rc, 200, new JsonObject().put("scopes", scopes));
  }

  /**
   * {@code GET /platform-docs/api/versions?site=<name>} — every published version, newest first.
   *
   * <p><b>A query parameter rather than a path segment</b>, and that is forced rather than chosen:
   * a site name carries slashes and usually a leading {@code @}, so {@code
   * /api/versions/@qits/ui-…} would need the same {@code /-/} grammar the reading routes use — for
   * a machine surface where nobody is reading the URL. The reading spelling of the same question is
   * {@code /platform-docs/<site>}, which answers a redirect because that is what a browser wants.
   */
  private void apiVersions(RoutingContext rc) {
    String site = rc.request().getParam("site");
    if (site == null || site.isBlank()) {
      DocsErrors.send(rc, 400, "?site= is required");
      return;
    }
    JsonArray listed = new JsonArray();
    for (DocsUpstream.Version version : upstream.versionDetails(site)) {
      listed.add(
          new JsonObject()
              .put("version", version.version())
              .put("fileCount", version.fileCount())
              .put("totalBytes", version.totalBytes())
              .put("publishedAt", version.publishedAt()));
    }
    if (listed.isEmpty()) {
      DocsErrors.send(rc, 404, "nothing is published under '" + site + "'");
      return;
    }
    respond(rc, 200, new JsonObject().put("name", site).put("versions", listed));
  }

  // --- plumbing ---------------------------------------------------------------------------------

  /**
   * A JSON body, built as a {@code JsonObject} rather than bound from a type.
   *
   * <p>A type serialised only inside a Vert.x handler is invisible to the native-image build, so
   * this stack adds zero reflection configuration — the rule qits-artifacts' four wire packages
   * follow, and the reason this service needs no {@code @RegisterForReflection} anywhere.
   */
  private static void respond(RoutingContext rc, int status, JsonObject body) {
    byte[] bytes = body.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    rc.response()
        .setStatusCode(status)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(bytes.length))
        // The catalog changes whenever anything publishes, so it must not be held.
        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        .end(io.vertx.core.buffer.Buffer.buffer(bytes));
  }

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
