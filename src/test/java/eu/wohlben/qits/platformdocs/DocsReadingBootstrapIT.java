package eu.wohlben.qits.platformdocs;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.MockService;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b>, beside a store — the one posture this repository's
 * suite has never had. {@link DocsPathsTest} and {@link DocsUpstreamParseTest} are deliberately
 * plain JUnit over the two pure pieces (the route grammar, the version parse), which leaves
 * everything <em>between</em> them untested: that a route registered at {@link DocsRoutes}' order
 * 20 000 really answers, that {@code qits.docs.artifacts-url} really reaches {@link DocsUpstream},
 * that the grouping happens here and not upstream, and that a failure behind this process comes out
 * as 502 in <b>plain text</b> rather than as {@code QuarkusErrorHandler}'s HTML page. All four are
 * properties of the built artifact plus its configuration, so nothing short of launching it can
 * show them.
 *
 * <p>The far side is a {@link MockService} impersonating qits-artifacts' docs repository, stubbed
 * with the store's own shapes ({@code {"sites":[…]}}, a version document with {@code files} and
 * {@code metadata}) — so the reading this proves is the reading a deployment does, and the
 * recordings make each interaction assertable on <b>both ends</b>: the reader acted on the answer,
 * and the store really was asked.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted
 * under {@code target/userstories/} with the interactions drawn as a sequence diagram. Both stories
 * are browserless (an {@code Interactions} parameter and no {@code Flow}), so the framework's
 * transitive Playwright never launches anything — which is what lets this run in a step container
 * with no browser in it.
 *
 * <p><b>The catalog is self-referential on purpose.</b> The site the first story reads is the
 * {@code userflows}-scoped bundle {@code .config/qits/ci-event-userflows.yml} publishes out of this
 * very run, into the store this service reads. A reader following the story arrives at the story.
 *
 * <p><b>This IT is the only one in the repository, so it opts the module back in</b> rather than
 * being named on a command line: {@code skipITs} is {@code false} in {@code pom.xml}, the
 * qits-githost shape, because there is no heavyweight sibling to drag into a plain {@code mvn
 * verify} — no docker, no database, no network beyond a loopback stub. {@code docker/Dockerfile}
 * stops at {@code package}, before the integration-test phase, so the image build is untouched by
 * that flip.
 */
@QuarkusIntegrationTest
@TestProfile(DocsReadingBootstrapIT.PackagedAgainstAMockStore.class)
public class DocsReadingBootstrapIT {

  static final String CATEGORY = "reading";
  static final String CATALOG_SLUG = "the-catalog-groups-every-published-site-by-its-scope";
  static final String REFUSAL_SLUG = "a-store-that-cannot-answer-is-never-an-empty-shelf";

  /** The service the mock impersonates — also the {@link MockService#ensureStarted} key. */
  static final String ARTIFACTS = "qits-artifacts";

  /**
   * The docs repository's path on the store, repository segment included — the tail of the real
   * {@code qits.docs.artifacts-url} ({@code http://dev-qits-artifacts:8080/artifacts/docs/docs}),
   * kept verbatim so every stubbed route below is the URL a deployment really builds.
   */
  static final String REPOSITORY_PATH = "/artifacts/docs/docs";

  static final String SITE = "@userflows/qits-docs";
  static final String SIBLING_SITE = "@userflows/qits-githost";
  static final String UNSCOPED_SITE = "qits-docs";
  static final String VERSION = "6f1d0c9b8a7e6d5c4b3a29180f1e2d3c4b5a6978";

  /** A site the store has never heard of: its 404 is what the reader must pass through. */
  static final String UNKNOWN_SITE = "@userflows/qits-nowhere";

  /** A site whose version document the store answers 200 with, and garbles. */
  static final String MANGLED_SITE = "@userflows/qits-mangled";

  static final String MANGLED_VERSION = "unreadable";

  /**
   * Marks the stubs as registered, for the same reason {@code MockIdp} parks its keypair: a test
   * profile is instantiated in more than one classloader and a static field written by one copy is
   * not the field another reads, while the JVM has exactly one property table. {@link
   * MockService#ensureStarted} already makes the <em>server</em> singular; the stubs live on the
   * owning instance, so this is what keeps the second copy from trying (and failing) to re-register
   * them on an attached handle.
   */
  private static final String STUBBED_PROPERTY = "qits.docs.it.store-stubbed";

  /**
   * Hands the launched artifact its config the way a deployment does — and there is strikingly
   * little of it, which is the shape of this service rather than an omission: it holds no state, so
   * {@code .config/qits/deployments.yml} declares no {@code resources:} at all and there are no
   * generic triples to supply. The whole of a qits-docs deployment is one address.
   *
   * <p>Both keys are <b>runtime</b> keys. A packaged process takes its configuration as {@code -D}
   * arguments on a jar that was already built, so a build-time key here would be silently ignored
   * and the test would prove something other than what it says.
   *
   * <p>The mock store starts here — before the application — via {@link #storeStartedAndStubbed()},
   * which parks its port in a system property; that is also how the story methods' {@link
   * MockService#attach} reaches the very server the launched process read from.
   */
  public static class PackagedAgainstAMockStore implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      MockService store = storeStartedAndStubbed();
      return Map.of(
          // The one seam this test moves: where the store is. The packaged artifact is otherwise
          // exactly what ships — the same routes, the same grouping, the same error envelope.
          "qits.docs.artifacts-url",
          store.baseUrl() + REPOSITORY_PATH,
          // Dark outside a deployment, like %dev/%test — a runtime key, and the only dial-out this
          // process has besides the store itself.
          "quarkus.otel.sdk.disabled",
          "true");
    }
  }

  /**
   * Start the mock of qits-artifacts once per JVM and stub the four routes these stories read.
   *
   * <p>The bodies are the store's own shapes, taken from qits-artifacts' {@code docs/DocsRoutes}
   * rather than invented: a flat {@code {"sites":[…]}} catalog whose entries carry {@code
   * latestPublishedAt} (which this service's {@code CatalogEntry} does not read — it is here so the
   * parse runs against the real payload), and a version document with {@code files} and dotted
   * {@code metadata} keys. Anything the stubs got wrong would make this test prove a store that
   * does not exist.
   */
  static synchronized MockService storeStartedAndStubbed() {
    if (System.getProperty(STUBBED_PROPERTY) != null) {
      return MockService.attach(ARTIFACTS);
    }
    MockService store = MockService.ensureStarted(ARTIFACTS);

    // The catalog, ORDERED BY NAME — the store's own ordering, which the grouping must preserve.
    // Two scoped sites and one unscoped, so the answer has to show both arms: a scope group and
    // the empty group a name with no scope goes under.
    store.stub(
        "GET",
        REPOSITORY_PATH,
        Map.of(
            "sites",
            List.of(
                catalogEntry(SITE, 2, VERSION),
                catalogEntry(SIBLING_SITE, 1, "b2c3d4e5f60718293a4b5c6d7e8f90123456789a"),
                catalogEntry(UNSCOPED_SITE, 3, "2026.827.163649"))));

    // One version's whole document. `files` is what tells a client the bundle's shape, and the
    // paths are this run's own story directories — the bundle this pipeline publishes, described.
    store.stub(
        "GET",
        REPOSITORY_PATH + "/" + SITE + "/-/" + VERSION,
        Map.of(
            "name",
            SITE,
            "version",
            VERSION,
            "fileCount",
            4,
            "totalBytes",
            18432,
            "publishedAt",
            "2026-08-29T09:15:00Z",
            "files",
            List.of(
                CATEGORY + "/" + CATALOG_SLUG + "/user-story.md",
                CATEGORY + "/" + CATALOG_SLUG + "/userflow.json",
                CATEGORY + "/" + REFUSAL_SLUG + "/user-story.md",
                CATEGORY + "/" + REFUSAL_SLUG + "/userflow.json"),
            "metadata",
            Map.of(
                "git.branch.name", "main",
                "git.commit.hash", VERSION,
                "git.repository.name", "qits-docs")));

    // The store answering 200 with something that is not a version document. The mock serializes
    // this string as a bare JSON string, which is exactly the case DocsUpstream guards: valid JSON
    // that is not the shape promised. UNKNOWN_SITE gets NO stub at all — an unstubbed route is a
    // 404 here, which is the store's genuine "no such thing" and needs no arrangement.
    store.stub(
        "GET",
        REPOSITORY_PATH + "/" + MANGLED_SITE + "/-/" + MANGLED_VERSION,
        200,
        "the docs repository is rebuilding");

    System.setProperty(STUBBED_PROPERTY, "true");
    return store;
  }

  /** One catalog row, exactly as qits-artifacts' {@code sites} route describes it. */
  private static Map<String, Object> catalogEntry(String name, int versions, String latest) {
    return Map.of(
        "name", name,
        "versionCount", versions,
        "latestVersion", latest,
        "latestPublishedAt", "2026-08-29T09:15:00Z");
  }

  @UserStory(value = "The catalog groups every published site by its scope", category = "reading")
  @UserStoryDescription(
      """
      A reader opening the documentation front door sees every published site arranged under its
      scope — `@userflows/qits-docs` beside `@userflows/qits-githost`, and a name with no scope
      under a group of its own. qits-artifacts answers a FLAT list and is right to: a scope lives
      in a site's name, and deciding what it groups under is a reading choice, so it is this
      service's. The store is asked exactly once, and nothing is remembered afterwards — which is
      what lets a bundle published a second ago be in the next answer.

      Then the reader opens one site: the version document comes back the store's, verbatim,
      `files` and `metadata` included — parsed only to prove it is JSON, never rebuilt member by
      member, so a member the store adds tomorrow reaches the client with no edit here.
      """)
  void theCatalogIsGroupedHereAndTheVersionDocumentIsPassedThrough(Interactions story) {
    MockService store = MockService.attach(ARTIFACTS);

    story.note("qits-docs starts beside a qits-artifacts holding three published docs sites");
    given().get("/docs/q/health/ready").then().statusCode(200);

    // End (a), the reader's: the flat list came back grouped, in the store's own order. The empty
    // group is the assertion worth spelling out — a service documenting itself as `qits-docs` has
    // no scope and must still be findable, so it is grouped rather than hidden.
    given()
        .get("/docs/api/sites")
        .then()
        .statusCode(200)
        .body("scopes[0].scope", equalTo("@userflows"))
        .body("scopes[0].docs[0].name", equalTo(SITE))
        .body("scopes[0].docs[0].shortName", equalTo("qits-docs"))
        .body("scopes[0].docs[0].versionCount", equalTo(2))
        .body("scopes[0].docs[1].name", equalTo(SIBLING_SITE))
        .body("scopes[1].scope", equalTo(""))
        .body("scopes[1].docs[0].name", equalTo(UNSCOPED_SITE))
        .body("scopes[1].docs[0].shortName", equalTo(UNSCOPED_SITE));

    // End (b), the store's: it was asked ONCE, for the flat catalog it holds — not per site, and
    // not again. That count is the whole of "this service holds no state and needs none": there is
    // no cache to have been warmed and no second opinion to disagree with the first.
    assertEquals(
        1,
        requestsTo(store, REPOSITORY_PATH),
        "the catalog should be one upstream read, and exactly one");

    story.happened("a reader", "qits-docs", "GET /docs/api/sites").as("catalog-read");
    story
        .happened("qits-docs", "qits-artifacts", "GET /artifacts/docs/docs (the flat catalog)")
        .as("catalog-asked-once");

    // The second half: one site's version document, passed through rather than rebuilt. The
    // metadata keys are the store's dotted ones, which is why they are read off an extracted map
    // rather than through a path expression that would have to quote them.
    Map<String, String> metadata =
        given()
            .queryParam("site", SITE)
            .queryParam("version", VERSION)
            .get("/docs/api/version")
            .then()
            .statusCode(200)
            .body("version", equalTo(VERSION))
            .body("fileCount", equalTo(4))
            .body("files[0]", equalTo(CATEGORY + "/" + CATALOG_SLUG + "/user-story.md"))
            .extract()
            .path("metadata");
    assertEquals(
        "qits-docs",
        metadata.get("git.repository.name"),
        "the store's metadata must reach the reader unedited");
    assertEquals(1, requestsTo(store, REPOSITORY_PATH + "/" + SITE + "/-/" + VERSION));

    story
        .happened("a reader", "qits-docs", "GET /docs/api/version (one site, one version)")
        .as("version-document-read");
    story
        .happened(
            "qits-docs", "qits-artifacts", "GET /artifacts/docs/docs/<site>/-/<version> (verbatim)")
        .as("version-document-fetched");
  }

  @UserStory(value = "A store that cannot answer is never an empty shelf", category = "reading")
  @UserStoryDescription(
      """
      The flip side of holding no state: everything this service says about what exists, it says
      on the store's authority — so the two ways that can go wrong must not look the same.

      A site the store has never heard of is a 404, passed through: the URL names nothing, and
      whoever typed it should go and fix the URL. A store that answers something that is not a
      version document is a 502: the failure is BEHIND this process, and whoever is debugging
      should go and look at qits-artifacts. Collapsing them — or, worse, reporting either as an
      empty catalog — sends a reader to the wrong place, and an empty shelf is the reading a
      documentation site can least afford to invent.

      Both answers are plain text, never HTML. The client is a browser assembling a website, so an
      HTML error body is precisely what it renders in place of the page that was asked for.
      """)
  void theStoresNoAndItsNonsenseAreDifferentAnswers(Interactions story) {
    MockService store = MockService.attach(ARTIFACTS);

    // (a) the store's no. Nothing is stubbed for this site, so the mock answers 404 — which is
    // exactly what qits-artifacts answers for a site it does not hold.
    String refusal =
        given()
            .queryParam("site", UNKNOWN_SITE)
            .get("/docs/api/versions")
            .then()
            .statusCode(404)
            .contentType(startsWith("text/plain"))
            .extract()
            .asString();
    assertTrue(
        refusal.contains("nothing is published under '" + UNKNOWN_SITE + "'"),
        "a 404 must name the site that is missing, in plain text: " + refusal);
    assertEquals(
        1,
        requestsTo(store, REPOSITORY_PATH + "/" + UNKNOWN_SITE),
        "the 404 must be the STORE's answer, not a guess made here");

    story
        .happened("a reader", "qits-docs", "GET /docs/api/versions (unknown site) -> 404")
        .as("unknown-site-refused");
    story
        .happened("qits-docs", "qits-artifacts", "GET /artifacts/docs/docs/<site> -> 404")
        .as("store-said-no");

    // (b) the store's nonsense. It answers 200 and something that is not a version document, which
    // is the one failure a reader could otherwise mistake for content.
    String broken =
        given()
            .queryParam("site", MANGLED_SITE)
            .queryParam("version", MANGLED_VERSION)
            .get("/docs/api/version")
            .then()
            .statusCode(502)
            .contentType(startsWith("text/plain"))
            .extract()
            .asString();
    assertTrue(
        broken.contains("not a version document"),
        "a 502 must say the store answered something else: " + broken);

    story
        .happened("a reader", "qits-docs", "GET /docs/api/version (store garbled) -> 502")
        .as("garbled-store-is-a-502");
    story
        .happened(
            "qits-docs",
            "qits-artifacts",
            "GET /artifacts/docs/docs/<site>/-/<version> -> 200, not a document")
        .as("store-answered-nonsense");
  }

  /** How many times the mock store answered exactly {@code path} (query strings excluded). */
  private static long requestsTo(MockService store, String path) {
    return store.recordedRequests().stream().filter(request -> path.equals(request.path())).count();
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, CATALOG_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY,
        CATALOG_SLUG,
        "qits-docs",
        "qits-artifacts",
        "GET /artifacts/docs/docs (the flat catalog)");
    ReportAssertions.assertStepId(CATEGORY, CATALOG_SLUG, "catalog-read");
    ReportAssertions.assertStepId(CATEGORY, CATALOG_SLUG, "catalog-asked-once");
    ReportAssertions.assertStepId(CATEGORY, CATALOG_SLUG, "version-document-read");
    ReportAssertions.assertStepId(CATEGORY, CATALOG_SLUG, "version-document-fetched");

    ReportAssertions.assertComplete(CATEGORY, REFUSAL_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "unknown-site-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "store-said-no");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "garbled-store-is-a-502");
    ReportAssertions.assertStepId(CATEGORY, REFUSAL_SLUG, "store-answered-nonsense");
  }
}
