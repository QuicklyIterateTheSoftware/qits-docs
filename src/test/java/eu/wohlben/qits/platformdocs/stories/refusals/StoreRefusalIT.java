package eu.wohlben.qits.platformdocs.stories.refusals;

import static io.restassured.RestAssured.given;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>Four ways the store can fail a reader, and four different things to say about them.</b> The
 * flip side of holding no state: everything qits-docs says about what exists, it says on the
 * store's authority — so every way that can go wrong has to come out looking different, or a reader
 * is sent to the wrong place.
 *
 * <p>The line that matters is <b>404 versus 502</b>. 404 means the URL names nothing and whoever
 * typed it should go and fix the URL; 502 means the failure is behind this process and whoever is
 * debugging should go and look at qits-artifacts. Collapsing them is the most expensive wrong
 * answer a component in the middle can give — and reporting either one as an <i>empty catalogue</i>
 * would be worse still, because an empty shelf is the reading a documentation site can least afford
 * to invent.
 *
 * <p>Three of the four are 502, and they reach it by three different routes through {@code
 * DocsUpstream}: a status it read and refused, a body it could not parse, and a socket that never
 * answered. The outgoing labels are what tell them apart — {@code -> 503}, {@code -> 200} and
 * {@code -> no answer} — which is why the store's recording carries the status it answered with and
 * not only what it was asked.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class StoreRefusalIT {

  static final String CATEGORY = "refusals";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY = "A store that cannot answer is never an empty shelf";

  static final String SLUG = Slugs.slug(STORY);

  private static final String INDEX = "index.html";

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryNetwork.install();
  }

  /**
   * Belt as well as braces. Every arming below is already in a {@code try}/{@code finally}; an
   * outage that outlived this story would be a broken store in somebody else's diagram, and the two
   * would look exactly alike.
   */
  @AfterEach
  void theStoreAnswersNormallyAgain() {
    StoryStore.answerNormally();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      A site the store has never heard of is a 404, passed through: the URL names nothing, and the
      reader is the one who can fix it. Everything else here is a 502, because the failure is behind
      this process — and the three of them are three genuinely different failures that this service
      must not be able to tell apart by guessing.

      The store ANSWERS SOMETHING ELSE. A 200 carrying valid JSON that is not a version document is
      the one failure a reader could otherwise mistake for content: parsed far enough to prove it is
      JSON, it is not the shape promised, and the honest answer is "qits-artifacts said something I
      cannot read".

      The store IS UP AND CANNOT SERVE. A 503 on the catalogue is not an empty catalogue. This is
      the arm the whole story is named for: a component in the middle that answered `{"scopes":[]}`
      here would be telling a reader that nothing is published, which is a lie that reads exactly
      like the truth. And it is not hypothetical — the readiness probe of this service is
      deliberately the STOCK one, because a health check that dialled qits-artifacts would take this
      container down for an outage it is designed to survive by answering 502. Surviving it means
      saying so.

      The store ACCEPTS THE CONNECTION AND SAYS NOTHING. The other arm of the same exception, and it
      arrives as an IOException rather than as a status this service read. The reader gets the same
      502, and the diagram does not: the outgoing arrow says `no answer`, which is what the store's
      own recording knows and no status could have said.

      All four bodies are PLAIN TEXT, and here that is load-bearing rather than a preference. The
      client is a browser assembling a website, so an HTML error body is precisely what it renders
      in place of the page that was asked for — a failed stylesheet would come back looking like a
      page. Nothing in this service calls `rc.fail()`, because Quarkus' own failure handler answers
      with exactly that.
      """)
  @UserflowRunsAfter(DocsReadingBootstrapIT.class)
  void everyWayTheStoreFailsLooksDifferent(Interactions story) {
    NetworkCapture.actor(StoryTarget.READER);

    // (a) the store's no. Nothing is published under this name, and the store says so — which is
    // exactly what qits-artifacts answers for a site it does not hold.
    String unknown =
        given()
            .queryParam("site", StoryStore.UNKNOWN_SITE)
            .get(StoryTarget.VERSIONS_PATH)
            .then()
            .statusCode(404)
            .contentType(startsWith("text/plain"))
            .extract()
            .asString();
    assertTrue(
        unknown.contains("nothing is published under '" + StoryStore.UNKNOWN_SITE + "'"),
        "a 404 must name the site that is missing, in plain text: " + unknown);
    assertEquals(
        1,
        StoryStore.reads(StoryStore.sitePath(StoryStore.UNKNOWN_SITE)),
        "the 404 must be the STORE's answer, not a guess made here");

    story
        .note(
            "a site nothing is published under is a 404 in PLAIN TEXT that names it — and it is the"
                + " STORE's 404, passed through, not a guess made here")
        .as("unknown-site-is-the-stores-404");

    // (b) the store's nonsense: 200, and a payload that is not a version document.
    String garbled =
        given()
            .queryParam("site", StoryStore.MANGLED_SITE)
            .queryParam("version", StoryStore.MANGLED_VERSION)
            .get(StoryTarget.VERSION_PATH)
            .then()
            .statusCode(502)
            .contentType(startsWith("text/plain"))
            .extract()
            .asString();
    assertTrue(
        garbled.contains("not a version document"),
        "a 502 must say the store answered something else: " + garbled);

    story
        .note(
            "a store that answers 200 with something that is not a version document is a 502 — note"
                + " the outgoing arrow's status: the failure was the payload, not the code")
        .as("garbled-content-is-a-502");

    // (c) the outage. The catalogue is the route where an empty answer would be indistinguishable
    // from the truth, which is why it is the one armed here.
    try {
      StoryStore.refuse(StoryStore.REPOSITORY_PATH);
      String outage =
          given()
              .get(StoryTarget.SITES_PATH)
              .then()
              .statusCode(502)
              .contentType(startsWith("text/plain"))
              .header("Cache-Control", org.hamcrest.Matchers.equalTo("no-store"))
              .extract()
              .asString();
      assertTrue(
          outage.contains("HTTP " + StoryStore.REFUSED_STATUS),
          "a 502 must quote the status the store answered with: " + outage);
    } finally {
      StoryStore.answerNormally();
    }

    story
        .note(
            "the store up and refusing is a 502 that quotes its 503 — never an empty catalog. An"
                + " empty shelf reads exactly like the truth, and it is the one reading a"
                + " documentation front door must never invent")
        .as("an-outage-is-not-an-empty-shelf");
    story
        .note(
            "and the refusal itself is never cached: an error must not outlive the outage that"
                + " caused it, least of all a 404 for a version that is about to exist")
        .as("a-refusal-is-not-held");

    // (d) the socket that never answers — the other arm of the same exception, on the bundle wire.
    try {
      StoryStore.hangUp(StoryStore.filePath(StoryStore.UI_SITE, StoryStore.UI_OLDEST, INDEX));
      String silence =
          StoryTarget.browser()
              .get(StoryTarget.versionIndex(StoryStore.UI_SITE, StoryStore.UI_OLDEST))
              .then()
              .statusCode(502)
              .contentType(startsWith("text/plain"))
              .extract()
              .asString();
      assertTrue(
          silence.contains("could not be reached"),
          "a 502 for a dead socket must say the store could not be asked: " + silence);
    } finally {
      StoryStore.answerNormally();
    }

    story
        .note(
            "a store that accepts the connection and then says nothing is the same 502 to the"
                + " reader and a different arrow on the diagram — `no answer`, which the store's own"
                + " recording knows and no status could have told us")
        .as("silence-is-also-a-502");
  }

  @AfterAll
  static void theRefusalStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    // --- what the reader was told: one 404 and three 502s
    // -----------------------------------------
    in(StoryTarget.read(StoryTarget.VERSIONS_PATH, 404));
    in(StoryTarget.read(StoryTarget.VERSION_PATH, 502));
    in(StoryTarget.read(StoryTarget.SITES_PATH, 502));
    in(StoryTarget.read(StoryTarget.versionIndex(StoryStore.UI_SITE, StoryStore.UI_OLDEST), 502));

    // --- what the store did: four different things, and the labels are what say so ---------------
    out(StoryStore.read(StoryStore.sitePath(StoryStore.UNKNOWN_SITE), 404));
    out(
        StoryStore.read(
            StoryStore.versionPath(StoryStore.MANGLED_SITE, StoryStore.MANGLED_VERSION), 200));
    out(StoryStore.read(StoryStore.REPOSITORY_PATH, StoryStore.REFUSED_STATUS));
    out(
        StoryStore.asked(
            "GET",
            StoryStore.filePath(StoryStore.UI_SITE, StoryStore.UI_OLDEST, INDEX),
            StoryStore.NO_ANSWER));

    // EIGHT: four questions, four answers, and no retry, no fallback and no second opinion in
    // between. A refusal that had quietly re-asked — or reached for the catalogue to make a
    // suggestion — would be a ninth edge here.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 8);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, SLUG, List.of(StoryTarget.READER, StoryTarget.SERVICE));

    for (String step :
        List.of(
            "unknown-site-is-the-stores-404",
            "garbled-content-is-a-502",
            "an-outage-is-not-an-empty-shelf",
            "a-refusal-is-not-held",
            "silence-is-also-a-502")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, step);
    }
  }

  private static void in(String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, SLUG, NetworkEdge.HTTP, StoryTarget.READER, StoryTarget.SERVICE, label);
  }

  private static void out(String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, SLUG, NetworkEdge.HTTP, StoryTarget.SERVICE, StoryStore.SERVICE_NAME, label);
  }
}
