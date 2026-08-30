package eu.wohlben.qits.platformdocs;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.platformdocs.stories.support.StoryNetwork;
import eu.wohlben.qits.platformdocs.stories.support.StoryProfile;
import eu.wohlben.qits.platformdocs.stories.support.StoryStore;
import eu.wohlben.qits.platformdocs.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>The front door, and the boot behind it.</b> The whole service as it is <b>packaged</b>, beside
 * a store — the one posture this repository's suite did not have before userflows arrived.
 *
 * <p>{@link DocsPathsTest} and {@link DocsUpstreamParseTest} are deliberately plain JUnit over the
 * two pure pieces (the route grammar, the version parse), which leaves everything <em>between</em>
 * them untested: that a route registered at {@link DocsRoutes}' order 20 000 really answers, that
 * {@code qits.docs.artifacts-url} really reaches {@link DocsUpstream}, and that the scope grouping
 * happens <i>here</i> and not upstream. All three are properties of the built artifact plus its
 * configuration, so nothing short of launching it can show them.
 *
 * <p><b>This class owns the boot, and its edge count is what says the boot was quiet.</b> The far
 * side's recording is cumulative with no floor and this class sorts first of every story class in
 * the fork ({@code …platformdocs.DocsReadingBootstrapIT} before {@code …platformdocs.stories.*}),
 * so anything the launched process asked the store before any story ran would land in this story's
 * diagram. Exactly two edges is therefore a claim about startup as much as about the catalog:
 * <b>qits-docs dials nothing when it starts</b>. It has nothing to dial for — no JWKS to fetch, no
 * credential to mint, no registry to reconcile, no cache to warm — which is the same statelessness
 * that lets {@code latest} mean the newest version <i>now</i>.
 *
 * <p>The far side is {@link StoryStore}, a real listener speaking qits-artifacts' docs plane, and
 * the recordings make each interaction assertable on <b>both ends</b>: the reader acted on the
 * answer, and the store really was asked.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class DocsReadingBootstrapIT {

  public static final String CATEGORY = "reading";

  public static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  public static final String CATALOG = "The catalog groups every published site by its scope";

  public static final String CATALOG_SLUG = Slugs.slug(CATALOG);

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(value = CATALOG, category = CATEGORY)
  @UserStoryDescription(
      """
      A reader opening the documentation front door sees every published site arranged under its
      scope — a released component library under `@qits`, a sibling's userflow bundles under
      `@userflows`, and a name with no scope at all under a group of its own. qits-artifacts
      answers a FLAT list and is right to: a scope lives in a site's name, and deciding what it
      groups under is a reading choice, so it is this service's, and a different reader is free to
      make a different one.

      The unscoped group is the arm worth spelling out. A service documenting itself as `qits-docs`
      has no scope, and the choice is to group it rather than to hide it or to invent it a home —
      the client renders that group without a heading.

      The store is asked exactly once for all of it, and nothing is remembered afterwards. That is
      the whole of "this service holds no state and needs none": there is no cache to have been
      warmed, no second opinion to disagree with the first, and a bundle published a second ago is
      in the next answer.

      This is also the first story of the run, and the store's recording has no floor under it — so
      the two arrows below are every arrow that existed by the time it finished. Starting qits-docs
      costs the platform nothing: no key fetch, no credential, no reconcile.
      """)
  void theCatalogIsGroupedHereAndTheBootIsQuiet(Interactions story) {
    story.note(
        "qits-docs starts beside a qits-artifacts holding three published docs sites — a released"
            + " Storybook workbench, a sibling's userflow bundles, and one unscoped name");
    given().get(StoryTarget.READY_PATH).then().statusCode(200);

    // The actor is set BEFORE the call: the tap sees a request, never a narrative role, and this is
    // what makes every observed edge below read `a reader -> qits-docs`. It has to be set at all —
    // the framework resets the actor to its default at every story start, so nothing can leak in
    // from a story that ran before.
    NetworkCapture.actor(StoryTarget.READER);

    // End (a), the reader's: the flat list came back GROUPED, in the store's own order.
    given()
        .get(StoryTarget.SITES_PATH)
        .then()
        .statusCode(200)
        .body("scopes[0].scope", equalTo("@qits"))
        .body("scopes[0].docs[0].name", equalTo(StoryStore.UI_SITE))
        .body("scopes[0].docs[0].shortName", equalTo("ui-components"))
        .body("scopes[0].docs[0].versionCount", equalTo(3))
        .body("scopes[0].docs[0].latestVersion", equalTo(StoryStore.UI_NEWEST_SEEDED))
        .body("scopes[1].scope", equalTo("@userflows"))
        .body("scopes[1].docs[0].name", equalTo(StoryStore.GITHOST_USERFLOWS))
        .body("scopes[1].docs[0].shortName", equalTo("qits-githost"))
        .body("scopes[1].docs[0].versionCount", equalTo(2))
        // The arm that matters: an unscoped name is grouped under the EMPTY scope rather than
        // hidden, and its shortName is the whole name — computed, never assumed to be a suffix.
        .body("scopes[2].scope", equalTo(""))
        .body("scopes[2].docs[0].name", equalTo(StoryStore.UNSCOPED_SITE))
        .body("scopes[2].docs[0].shortName", equalTo(StoryStore.UNSCOPED_SITE));

    // End (b), the store's: it was asked ONCE, for the flat catalog it holds — not per site, and
    // not again.
    assertEquals(
        1,
        StoryStore.reads(StoryStore.REPOSITORY_PATH),
        "the catalog should be one upstream read, and exactly one");

    story
        .note(
            "the front door answers the flat list GROUPED — @qits first, then @userflows, and the"
                + " unscoped site under a group of its own rather than hidden")
        .as("catalog-grouped-here");
    // An ABSENCE is not an edge: "and not again" is a claim about traffic that did not happen, and
    // the diagram draws only traffic that did. The counted assertion above is the proof, and the
    // edge count below is what would notice a second read of anything else.
    story
        .note(
            "the store was read exactly once for it — this service holds no state, so there is no"
                + " cache to have been warmed and no second opinion to disagree with the first")
        .as("catalog-asked-once");
    story
        .note(
            "and these two arrows are all there is: the store's recording has no floor under it, so"
                + " a boot that had fetched a key, minted a credential or warmed anything would be"
                + " drawn right here")
        .as("the-boot-was-quiet");
  }

  @AfterAll
  static void theCatalogStoryIsComplete() {
    // The extension emits the report in its afterEach, so it is on disk before @AfterAll runs.
    // assertComplete also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY_SLUG, CATALOG_SLUG, UserflowReport.PASSED);

    // Near side, by the shipped tap. The readiness probe drew nothing — a `/q/` segment is what the
    // tap skips, and a diagram in which every node hangs off /docs/q/health/ready documents
    // nothing.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        CATALOG_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.READER,
        StoryTarget.SERVICE,
        StoryTarget.read(StoryTarget.SITES_PATH, 200));
    // Far side, drained from the store's own access log.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        CATALOG_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryStore.SERVICE_NAME,
        StoryStore.read(StoryStore.REPOSITORY_PATH));

    // EXACTLY those two, which is the story's other half: everything the launched process did up to
    // here is on the diagram, and there is nothing else on it.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, CATALOG_SLUG, 2);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, CATALOG_SLUG, List.of(StoryTarget.READER, StoryTarget.SERVICE));

    ReportAssertions.assertStepId(CATEGORY_SLUG, CATALOG_SLUG, "catalog-grouped-here");
    ReportAssertions.assertStepId(CATEGORY_SLUG, CATALOG_SLUG, "catalog-asked-once");
    ReportAssertions.assertStepId(CATEGORY_SLUG, CATALOG_SLUG, "the-boot-was-quiet");
  }
}
