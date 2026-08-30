package eu.wohlben.qits.platformdocs.stories.support;

import eu.wohlben.qits.userflows.Labels;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

/**
 * The one launched qits-docs, addressed the way every one of its surfaces is addressed — and named
 * the way a diagram names it.
 *
 * <p>This service has <b>one</b> plane and it is all under {@code /docs}: the machine surface
 * ({@code /docs/api/**}), the bundle wire ({@code /docs/<site>/-/<version>/**}) and Quarkus' own
 * non-application root ({@code /docs/q/**}, from {@code quarkus.http.non-application-root-path}).
 * The client Quinoa serves is at the host root and is not a surface any story here drives — every
 * story runs against a build with {@code -Dquarkus.quinoa=false}, so there is no bundle to serve.
 *
 * <p><b>The port is random</b> — failsafe launches the artifact with {@code
 * quarkus.http.test-port=0} — so nothing here is a constant except the paths, and RestAssured is
 * handed the port by the Quarkus integration-test extension.
 *
 * <h2>The shipped tap's default skip is right here, and it was checked</h2>
 *
 * <p>{@link eu.wohlben.qits.userflows.NetworkTaps#restAssured(String)} skips any path carrying a
 * {@code /q/} <b>segment</b> rather than a leading one. This service's probes live at {@code
 * /docs/q/health/ready} — nested under {@code /docs}, which is exactly the case the segment rule
 * exists for — and no route of this service can contain a {@code /q/} segment, because {@code
 * DocsPaths.NOT_RESERVED} refuses {@code q/} as the first segment of a site name and no fixture
 * bundle here has a {@code q} directory in it. So no story class overrides the predicate.
 *
 * <h2>Authored literals and generated ids, on purpose</h2>
 *
 * <p>{@link Labels} rewrites only the path segments it can tell were generated — a uuid, a long hex
 * run, a bare number — and this catalogue puts <b>both</b> kinds in one diagram set on purpose, so
 * the two rules are visible side by side:
 *
 * <ul>
 *   <li>A <b>userflows</b> bundle is version-addressed by the <b>commit sha</b> that produced it —
 *       forty lowercase hex characters, a whole path segment, so {@code Labels} rewrites it to
 *       {@code {digest}} and the label stays a template rather than moving with every publish. That
 *       is the right answer: the sha is run-local to the pipeline.
 *   <li>A <b>released</b> bundle is version-addressed by a <b>CalVer</b> string ({@code
 *       2026.830.0}), which is authored and survives a label verbatim — and it should, because
 *       {@code /docs/@qits/ui-components/-/2026.830.0/} is a URL a person pastes.
 *   <li>An asset inside a bundle carries a content hash <b>inside</b> its segment ({@code
 *       assets/main-a1b2c3d4.js}). {@code Labels} correctly refuses to rewrite that — it is not a
 *       whole segment — so the fixture's hash is <b>authored</b>. An id embedded in a segment has
 *       to be, or the {@code networkHash} moves every run with no symptom but a diff.
 * </ul>
 *
 * <p><b>A query string never reaches a label from the shipped tap.</b> It labels {@code METHOD
 * <scrubbed PATH> -> <status>} and drops the query entirely, which is load-bearing here: {@code
 * /docs/api/versions} is asked unfiltered, branch-filtered and filtered-to-nothing in one story and
 * all three draw <b>one</b> edge. What the reader asked for is a step; where this service went to
 * get it is the outgoing edge, and {@link StoryStore} deliberately keeps the query on <i>that</i>
 * side — see its javadoc.
 */
public final class StoryTarget {

  /** How every diagram in this catalogue names the service under test, on both sides of an edge. */
  public static final String SERVICE = "qits-docs";

  /** The narrative initiator of everything a story sends into this service. */
  public static final String READER = "a reader";

  /** {@code DocsPaths.BASE} — a literal in the code, so a literal here. */
  public static final String BASE = "/docs";

  /** {@code GET /docs/api/sites} — the catalog, grouped by scope. */
  public static final String SITES_PATH = BASE + "/api/sites";

  /** {@code GET /docs/api/versions?site=&branch=} — one site's versions, newest first. */
  public static final String VERSIONS_PATH = BASE + "/api/versions";

  /** {@code GET /docs/api/version?site=&version=} — one version's whole document. */
  public static final String VERSION_PATH = BASE + "/api/version";

  /** The readiness endpoint, which the tap skips — see the class javadoc. */
  public static final String READY_PATH = BASE + "/q/health/ready";

  private StoryTarget() {}

  /**
   * A request onto the <b>reading</b> routes, sent the way a browser sends one.
   *
   * <p>Two settings, and both are corrections of a RestAssured default that does not match any real
   * client:
   *
   * <ul>
   *   <li><b>{@code urlEncodingEnabled(false)}</b>. RestAssured percent-encodes {@code @} in a path
   *       to {@code %40}; no browser and no {@code curl} does, because {@code @} is perfectly legal
   *       in a path segment. It matters here because a site name usually <i>starts</i> with one,
   *       and {@code DocsPaths}' character classes admit no {@code %} — so an encoded request does
   *       not match the site route at all and falls through to Quarkus' own HTML 404. Sending the
   *       path verbatim is what makes these stories about this service rather than about its test
   *       client. The {@code /docs/api/**} routes are unaffected and keep the default: they are
   *       addressed by query parameter, where encoding is correct and Vert.x decodes it back.
   *   <li><b>{@code redirects().follow(false)}</b>. Everything this service adds beyond the
   *       passthrough <i>is</i> a redirect, so what the browser is told is the answer under test —
   *       and following it would draw an arrow at a client that is not even built in a {@code
   *       -Dquarkus.quinoa=false} run.
   * </ul>
   */
  public static RequestSpecification browser() {
    return RestAssured.given().urlEncodingEnabled(false).redirects().follow(false);
  }

  // --- the reading spellings ---------------------------------------------------------------------

  /** {@code /docs/<site>} — the human entry point, which redirects to the reader. */
  public static String sitePath(String site) {
    return BASE + "/" + site;
  }

  /** Where {@link #sitePath} lands: the client, at the root of this service's own host. */
  public static String readerPath(String site) {
    return "/read/" + site;
  }

  /** {@code /docs/<site>/-/<version>} — a version root, which redirects to its directory form. */
  public static String versionRoot(String site, String version) {
    return BASE + "/" + site + "/-/" + version;
  }

  /** {@code /docs/<site>/-/<version>/} — the directory form, which serves the bundle's index. */
  public static String versionIndex(String site, String version) {
    return versionRoot(site, version) + "/";
  }

  /** {@code /docs/<site>/-/<version>/<path>} — one file of a bundle. */
  public static String filePath(String site, String version, String path) {
    return versionRoot(site, version) + "/" + path;
  }

  // --- what an assertion has to spell ------------------------------------------------------------

  /**
   * The label the shipped RestAssured tap gives an incoming request — {@code METHOD <scrubbed path>
   * -> <status>}, scrubbed through the very function the tap uses so an assertion and an
   * observation can never disagree about what a generated segment became.
   */
  public static String served(String method, String path, int status) {
    return Labels.scrub(method + " " + path + " -> " + status);
  }

  /** {@code GET <path> -> <status>} — the shape of every edge a reader draws here. */
  public static String read(String path, int status) {
    return served("GET", path, status);
  }
}
