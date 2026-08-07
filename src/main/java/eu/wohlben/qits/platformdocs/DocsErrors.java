package eu.wohlben.qits.platformdocs;

import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

/**
 * The error envelope: a status code plus a short plain-text body.
 *
 * <p><b>Never HTML</b>, and here that is the load-bearing part rather than a preference. The client
 * is a browser rendering a documentation site, so an HTML error body is precisely what it will
 * display in place of the page that was asked for — a failed asset would come back looking like a
 * page. Quarkus installs {@code QuarkusErrorHandler} as the router's failure handler and it answers
 * with exactly that, which is why nothing here calls {@code rc.fail()}.
 */
final class DocsErrors {

  private static final Logger LOG = Logger.getLogger(DocsErrors.class);

  private DocsErrors() {}

  static void send(RoutingContext rc, int status, String message) {
    // A response may already be on its way: a reader that hung up mid-file leaves nothing to
    // answer,
    // and writing again throws IllegalStateException and buries the real cause.
    if (rc.response().ended() || rc.response().headWritten()) {
      return;
    }
    rc.response()
        .setStatusCode(status)
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
        // An error must never be cached — least of all a 404 for a version that is about to exist.
        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        .end(message);
  }

  /**
   * The safety net at the edge of every handler.
   *
   * <p>An unreachable store is a <b>502</b> and a missing file is a 404, and keeping them apart is
   * the most useful thing this service can say: 502 means the failure is behind this process, 404
   * means the URL names nothing. Collapsing them sends whoever is debugging to the wrong place.
   */
  static void fail(RoutingContext rc, String what, Throwable thrown) {
    switch (thrown) {
      case DocsUpstream.DocsUpstreamException e -> {
        LOG.warnf("docs: %s — %s", what, e.getMessage());
        send(rc, 502, e.getMessage());
      }
      default -> {
        LOG.errorf(thrown, "docs: %s", what);
        send(rc, 500, "internal docs error");
      }
    }
  }
}
