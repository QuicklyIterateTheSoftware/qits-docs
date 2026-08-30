package eu.wohlben.qits.platformdocs.stories.refusals;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platformdocs.DocsReadingBootstrapIT;
import eu.wohlben.qits.platformdocs.stories.support.StoryNetwork;
import eu.wohlben.qits.platformdocs.stories.support.StoryProfile;
import eu.wohlben.qits.platformdocs.stories.support.StoryStore;
import eu.wohlben.qits.platformdocs.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>The three answers this service gives on its own authority.</b> Everything else qits-docs says
 * about what exists it says on the store's — which makes the handful of answers it gives <i>without
 * asking</i> worth a diagram of their own, and the diagram's whole subject is that it has no
 * outgoing arrow on it.
 *
 * <p>This is the story a presence check cannot make. Three requests came in, three answers went
 * out, and a store that was up and recording every single call it received was never dialled. It is
 * the assertion that would notice a convenience creeping in — a "resolve the site so the 404 can
 * suggest a near match", a "check the version exists before redirecting" — each of which would turn
 * a free answer into a round trip, on routes a browser hits constantly.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class QuietRefusalIT {

  static final String CATEGORY = "refusals";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY = "The answers that cost the store nothing";

  static final String SLUG = Slugs.slug(STORY);

  /** A scope, not a site: one segment beginning with {@code @} and no slash after it. */
  private static final String SCOPE = "@userflows";

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      qits-docs holds no state, so almost every answer it gives is the store's. Three are not, and
      each of the three is a decision rather than an omission.

      A SCOPE IS NOT A SITE. `/docs/@userflows` is one segment beginning with `@`, which is a
      namespace and not something with a newest version to redirect to — so it is a 404 that says
      so, in plain text, decided from the URL alone. It used to fall through to a client page under
      this segment; the client is at the root of this service's host now and `/docs` is an ignored
      prefix, so the fall-through would have reached Quarkus' own HTML 404 instead — which is the
      one body shape this service must never produce, because its client is a browser assembling a
      website and would render it in place of the page that was asked for.

      A MISSING `?site=` IS A 400. The version list is addressed by query parameter because a site
      name spans path segments, and a request that names no site is malformed rather than pointing
      at nothing. Asking the store for the versions of `null` to find that out would be a round trip
      spent confirming what the request already said.

      AND THE TRAILING-SLASH REDIRECT ASKS NOTHING. `/docs/<site>/-/<version>` answers 302 to the
      same path with a slash on it, without checking that the version exists: the target answers
      that itself, and asking twice would cost a reader a round trip to be told the same thing. It
      is the route a browser hits on the way into every bundle it opens.

      So: three requests in, three answers out, and a store that was up and recording every call it
      received was never dialled. That absence is the story — and it is what a presence check could
      not have said.
      """)
  @UserflowRunsAfter(DocsReadingBootstrapIT.class)
  void threeAnswersReachNothingAtAll(Interactions story) {
    NetworkCapture.actor(StoryTarget.READER);

    long readsBefore = StoryStore.reads(StoryStore.REPOSITORY_PATH);

    // (a) a scope. There is no newest version of a namespace.
    String scopeRefusal =
        StoryTarget.browser()
            .get(StoryTarget.sitePath(SCOPE))
            .then()
            .statusCode(404)
            .contentType(startsWith("text/plain"))
            .header("Cache-Control", equalTo("no-store"))
            .extract()
            .asString();
    assertTrue(
        scopeRefusal.contains("'" + SCOPE + "' is a scope, not a site"),
        "the 404 must say what a scope is, in plain text: " + scopeRefusal);
    assertEquals(
        0,
        StoryStore.reads(StoryStore.sitePath(SCOPE)),
        "a scope must never be looked up as a site");

    story
        .note(
            "/docs/@userflows is a scope and answers a plain-text 404 saying so — decided from the"
                + " url, with nothing asked of anybody. Never HTML: an HTML error body is exactly"
                + " what a browser renders in place of the page it wanted")
        .as("a-scope-is-not-a-site");

    // (b) a malformed request. The site is the whole question, so a request without one is not a
    // question the store could answer.
    String missingSite =
        given()
            .get(StoryTarget.VERSIONS_PATH)
            .then()
            .statusCode(400)
            .contentType(startsWith("text/plain"))
            .extract()
            .asString();
    assertTrue(
        missingSite.contains("?site= is required"),
        "the 400 must name the parameter that is missing: " + missingSite);

    story
        .note(
            "a version list with no ?site= on it is a 400, not a lookup — the request already says"
                + " it names nothing, and confirming that upstream would be a round trip spent"
                + " agreeing with it")
        .as("a-missing-site-is-a-400");

    // (c) the slash. A browser hits this on the way into every bundle it opens.
    StoryTarget.browser()
        .get(StoryTarget.versionRoot(StoryStore.UI_SITE, StoryStore.UI_OLDEST))
        .then()
        .statusCode(302)
        .header(
            "Location", equalTo(StoryTarget.versionIndex(StoryStore.UI_SITE, StoryStore.UI_OLDEST)))
        .header("Cache-Control", equalTo("no-store"));
    assertEquals(
        0,
        StoryStore.reads(StoryStore.versionPath(StoryStore.UI_SITE, StoryStore.UI_OLDEST)),
        "the trailing-slash redirect must not check the version exists");

    story
        .note(
            "the version root redirects to its directory form without checking the version is"
                + " there — the target answers that itself, and this is the route a browser hits on"
                + " the way into every bundle")
        .as("the-redirect-is-free");

    // The claim, counted: the store answered nothing at all for any of the three.
    assertEquals(
        readsBefore, StoryStore.reads(StoryStore.REPOSITORY_PATH), "not even the catalog was read");

    story
        .note(
            "three requests in, three answers out, and the store — up, and recording every call it"
                + " received — was never dialled. The diagram below has no outgoing arrow on it,"
                + " and that emptiness is the whole story")
        .as("nothing-was-asked");
  }

  @AfterAll
  static void theQuietStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    edge(StoryTarget.read(StoryTarget.sitePath(SCOPE), 404));
    edge(StoryTarget.read(StoryTarget.VERSIONS_PATH, 400));
    edge(StoryTarget.read(StoryTarget.versionRoot(StoryStore.UI_SITE, StoryStore.UI_OLDEST), 302));

    // The three negative claims, each saying something the other two cannot.
    // Nothing reached the store — the claim about the thing being spared.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryStore.SERVICE_NAME);
    // Nothing left this process at all — the same fact from the other side, which would also catch
    // a second far side appearing (a span export, a peer nobody expected).
    ReportAssertions.assertNoEdgesFrom(CATEGORY_SLUG, SLUG, StoryTarget.SERVICE);
    // …and only the reader ever initiated anything, which is what catches a leaked default actor.
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY_SLUG, SLUG, List.of(StoryTarget.READER));
    // Three, so a fourth request of any kind is visible even if it went nowhere either.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 3);

    for (String step :
        List.of(
            "a-scope-is-not-a-site",
            "a-missing-site-is-a-400",
            "the-redirect-is-free",
            "nothing-was-asked")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, step);
    }
  }

  private static void edge(String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, SLUG, NetworkEdge.HTTP, StoryTarget.READER, StoryTarget.SERVICE, label);
  }
}
