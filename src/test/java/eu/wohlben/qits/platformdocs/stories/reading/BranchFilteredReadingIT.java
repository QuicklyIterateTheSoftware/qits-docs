package eu.wohlben.qits.platformdocs.stories.reading;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>The branch filter, and where it is applied.</b> Documentation is published per commit and
 * therefore per branch, so "which version of this is the one my branch produced" is an ordinary
 * question — and the interesting half of the answer is that qits-docs does not answer it. It hands
 * the question to the store.
 *
 * <p>Three reads of one route, and they draw <b>one</b> incoming edge between them: the shipped tap
 * labels {@code METHOD <path> -> <status>} and drops the query entirely, so query-variant routes
 * are one edge in the diagram. Every distinction the story is about is on the <i>outgoing</i> side,
 * which is exactly where it belongs — the filter is a thing this service forwards, not a thing it
 * does.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class BranchFilteredReadingIT {

  static final String CATEGORY = "reading";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY = "The branch filter is the store's question, not this service's";

  static final String SLUG = Slugs.slug(STORY);

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      A userflows bundle is published once per commit, so one site holds versions from several
      branches at once and a reader on a work branch wants the one their branch produced — not the
      one that shipped. `/docs/api/versions?site=…&branch=…` is that question.

      qits-docs does not answer it. The branch is pushed upstream as qits-artifacts' own metadata
      filter, `?meta.git.branch.name=`, and the store's matching AND ordering stay the single source
      of truth for "the branch's latest". Filtering the full list here would be a second opinion
      that can disagree with the store's, over data this service does not own — and it would still
      be wrong the moment the store learned a filter this one had not.

      The branch name is percent-encoded on the way, and the outgoing arrow shows it:
      `environment/dev` leaves as `environment%2Fdev`. That is not decoration — a branch name is a
      query VALUE off the reader's own query string and can carry `/`, `#` and spaces, unlike the
      site path beside it, whose character classes exclude every one of them.

      The third read is the one that has to be an ANSWER rather than a refusal: a site the store
      knows, filtered to nothing, is 200 with an empty list. A branch-latest probe has to be able to
      render "no bundle for this branch yet", and a 404 there would say the site does not exist.
      The unfiltered-and-empty case does not arise at all — a site exists only by having a bundle
      published under it — so no 404 arm is lost.

      All three go out on the same route, and the diagram draws them as one incoming arrow: a
      query never reaches a label. What each read asked for is a step; where this service went to
      get it is the arrow.
      """)
  @UserflowRunsAfter(DocsReadingBootstrapIT.class)
  void theBranchFilterIsPushedUpstream(Interactions story) {
    NetworkCapture.actor(StoryTarget.READER);

    // (a) unfiltered: everything the site holds, in the store's own order — newest first.
    Map<String, String> newest =
        given()
            .queryParam("site", StoryStore.GITHOST_USERFLOWS)
            .get(StoryTarget.VERSIONS_PATH)
            .then()
            .statusCode(200)
            .body("name", equalTo(StoryStore.GITHOST_USERFLOWS))
            .body("versions", hasSize(2))
            .body("versions[0].version", equalTo(StoryStore.GITHOST_MAIN_VERSION))
            .body("versions[1].version", equalTo(StoryStore.GITHOST_DEV_VERSION))
            .extract()
            .path("versions[0].metadata");
    // The store's keys are DOTTED, so they are read off an extracted map rather than through a path
    // expression that would have to quote them — and they arrive unedited, which is the point.
    assertEquals("main", newest.get("git.branch.name"));

    story
        .note(
            "unfiltered, the site holds two bundles — one from main and one from environment/dev —"
                + " newest first, in the store's own order")
        .as("both-branches");

    // (b) filtered to the environment branch: one version, and it is the older one. A local filter
    // over (a)'s answer would have produced the same list HERE, which is exactly why the proof is
    // the outgoing arrow rather than the body.
    Map<String, String> filtered =
        given()
            .queryParam("site", StoryStore.GITHOST_USERFLOWS)
            .queryParam("branch", StoryStore.DEV_BRANCH)
            .get(StoryTarget.VERSIONS_PATH)
            .then()
            .statusCode(200)
            .body("versions", hasSize(1))
            .body("versions[0].version", equalTo(StoryStore.GITHOST_DEV_VERSION))
            .extract()
            .path("versions[0].metadata");
    assertEquals(StoryStore.DEV_BRANCH, filtered.get("git.branch.name"));

    // End (b), the store's: it was asked WITH the filter on it, percent-encoded. Only this proves
    // the filter left the process at all.
    assertEquals(
        1,
        StoryStore.readsMatching(
            StoryStore.sitePath(StoryStore.GITHOST_USERFLOWS, StoryStore.DEV_BRANCH)),
        "the branch must reach the store as its own metadata filter, percent-encoded");

    story
        .note(
            "asked for one branch, the store is asked for one branch: ?branch=environment/dev goes"
                + " out as ?meta.git.branch.name=environment%2Fdev, encoded because a branch name"
                + " can carry a slash where a site path cannot")
        .as("filter-pushed-upstream");

    // (c) a branch nothing was published from: an ANSWER, not a refusal.
    given()
        .queryParam("site", StoryStore.GITHOST_USERFLOWS)
        .queryParam("branch", StoryStore.ABSENT_BRANCH)
        .get(StoryTarget.VERSIONS_PATH)
        .then()
        .statusCode(200)
        .body("name", equalTo(StoryStore.GITHOST_USERFLOWS))
        .body("versions", hasSize(0));

    story
        .note(
            "a site the store knows, filtered to nothing, is 200 with an empty list — 'no bundle"
                + " for this branch yet' is an answer a probe has to be able to render, and a 404"
                + " there would say the site does not exist")
        .as("filtered-to-nothing-is-an-answer");

    assertEquals(
        3,
        StoryStore.reads(StoryStore.sitePath(StoryStore.GITHOST_USERFLOWS)),
        "three reads of one site, one per question asked");
  }

  @org.junit.jupiter.api.AfterAll
  static void theBranchStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    // ONE incoming edge for three reads — the shipped tap drops the query, so query-variant routes
    // collapse. That is the trap worth having drawn once in this catalogue.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        StoryTarget.READER,
        StoryTarget.SERVICE,
        StoryTarget.read(StoryTarget.VERSIONS_PATH, 200));

    // Three outgoing edges, and they differ ONLY in the query — which is why this catalogue keeps
    // the query on the outgoing label. An authored branch name survives the scrubber verbatim; a
    // generated value (a uuid, a long hex run) would not, which is the rule that makes the label
    // safe to hash.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryStore.SERVICE_NAME,
        StoryStore.read(StoryStore.sitePath(StoryStore.GITHOST_USERFLOWS)));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryStore.SERVICE_NAME,
        StoryStore.read(StoryStore.sitePath(StoryStore.GITHOST_USERFLOWS, StoryStore.DEV_BRANCH)));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryStore.SERVICE_NAME,
        StoryStore.read(
            StoryStore.sitePath(StoryStore.GITHOST_USERFLOWS, StoryStore.ABSENT_BRANCH)));

    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 4);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, SLUG, List.of(StoryTarget.READER, StoryTarget.SERVICE));

    ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, "both-branches");
    ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, "filter-pushed-upstream");
    ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, "filtered-to-nothing-is-an-answer");
  }
}
