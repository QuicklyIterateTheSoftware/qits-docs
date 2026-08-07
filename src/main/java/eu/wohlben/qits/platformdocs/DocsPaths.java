package eu.wohlben.qits.platformdocs;

/**
 * The route grammar for {@code /platform-docs}.
 *
 * <p><b>It is deliberately the same grammar qits-artifacts serves under {@code /artifacts/docs}</b>
 * — the {@code /-/} separator between a multi-segment site name and its version, and the same
 * character classes. A reader's URL and a publisher's URL differ only in their prefix, so a version
 * copied out of a release log resolves here by pasting rather than by translating, and nothing in
 * this service has to convert one shape into the other.
 *
 * <p><b>Every group here is either {@code (?<name>…)} or {@code (?:…)}, never a bare {@code
 * (…)}.</b> vertx-web compares {@code Matcher.groupCount()} against the named groups it scraped out
 * of the pattern and silently falls back to positional {@code param0…paramN} when the two disagree
 * — so one stray capturing group breaks every {@code pathParam(…)} on that route, at runtime, with
 * no error anywhere.
 *
 * <p>Matching runs against {@code normalizedPath()}, which collapses dot-segments before routing —
 * so {@code ..} never reaches a handler and a file path cannot be walked out of its version.
 *
 * <p>What this service adds beyond the publisher's grammar is the two <b>reading</b> spellings:
 * {@link #SITE_LATEST}, which resolves to the newest version, and the trailing-slash form of a
 * version root. Both are redirects rather than content, because a documentation bundle's own HTML
 * refers to its assets relatively — so what the browser must end up on is a directory URL, and
 * anything that served content from a non-directory URL would give a page whose every asset 404s.
 */
final class DocsPaths {

  private DocsPaths() {}

  /**
   * The mount point. A literal here exactly as it is in qits-artifacts' wire packages: no config
   * key moves it, because {@code qits-gateway} routes verbatim by prefix and this segment is
   * derived from this repository's own name.
   */
  static final String BASE = "/platform-docs";

  /**
   * One component of a site name. The leading {@code @} is optional and the character after it is
   * not: that is the rule that makes a bare {@code -} unmatchable and the {@code /-/} separator
   * unambiguous.
   */
  private static final String SEGMENT = "(?:@?[A-Za-z0-9][A-Za-z0-9._~-]{0,127})";

  /**
   * The machine surface a site name may never claim: Quarkus' non-application root ({@code
   * /platform-docs/q/**}, which is where health lives) and this service's own {@code
   * /platform-docs/api/**}.
   *
   * <p><b>This is structural on purpose rather than a matter of route order.</b> Both are ordinary
   * site names as far as the character classes are concerned — {@code q/health/ready} is three
   * perfectly valid segments — so without this lookahead a readiness probe resolves as a request
   * for a site called {@code q/health/ready}, answers 404, and the deployment's health gate never
   * goes green while the process is running fine. Registration order would also fix it, and would
   * keep being right only until someone reordered {@code DocsRoutes.init} for readability.
   *
   * <p>Zero-width, so it costs the named-group count nothing — which matters, see the class
   * javadoc.
   */
  private static final String NOT_RESERVED = "(?!q/|api/)";

  /**
   * {@code <site>} — {@code ui-components}, {@code @qits/ui-components}, or a deeper project path.
   */
  private static final String NAME =
      "(?<name>" + NOT_RESERVED + SEGMENT + "(?:/" + SEGMENT + "){0,3})";

  private static final String VERSION = "(?<version>[A-Za-z0-9][A-Za-z0-9._+-]{0,127})";

  private static final String PATH = "(?<path>.+)";

  /** {@code /platform-docs/<site>/} — the newest version, as a redirect. */
  static final String SITE_LATEST = route(NAME + "/");

  /** {@code /platform-docs/<site>} — the same, without the slash. Also a redirect. */
  static final String SITE = route(NAME);

  /**
   * {@code /platform-docs/<site>/-/<version>} — a version root, redirected to its directory form.
   */
  static final String VERSION_ROOT = route(NAME + "/-/" + VERSION);

  /** {@code /platform-docs/<site>/-/<version>/} — the directory form, which serves the index. */
  static final String VERSION_INDEX = route(NAME + "/-/" + VERSION + "/");

  /** {@code /platform-docs/<site>/-/<version>/<path>} — one file. */
  static final String FILE = route(NAME + "/-/" + VERSION + "/" + PATH);

  /**
   * Builds a route regex under {@link #BASE}.
   *
   * <p>A method call rather than string concatenation, and that is not styling — it is the reason
   * qits-artifacts' path classes give: a {@code static final String} initialised from a constant
   * expression is inlined by javac into every class that reads it, including the test, which would
   * then keep asserting against whatever the value was when it was last compiled.
   */
  private static String route(String suffix) {
    return BASE + "/" + suffix;
  }
}
