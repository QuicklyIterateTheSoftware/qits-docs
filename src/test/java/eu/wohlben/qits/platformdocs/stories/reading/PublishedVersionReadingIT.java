package eu.wohlben.qits.platformdocs.stories.reading;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>Publish, then read — the whole arc, both ends.</b> A release publishes a documentation bundle
 * into qits-artifacts, and a reader opens it: catalogue, version resolution, two redirects, the
 * entry point and one asset. Nine arrows, two initiators, and one component in the middle that
 * remembers none of it.
 *
 * <p>The publish is a real {@code PUT} onto the store's docs plane, from outside this process. That
 * matters: <b>"a version published a second ago is already the newest" is not a claim a pre-seeded
 * fixture can make</b>. The list is read before the publish and again after it, and the arrow into
 * the store is identical both times — no invalidation, no warm-up, no second address — because
 * there is nothing here that could have gone stale.
 *
 * <p>The two redirects are the whole of what this service adds beyond the passthrough, and they are
 * different from each other in a way the diagram shows: the first costs a store read (a 404 for a
 * site nothing is published under is worth answering before a page load), the second costs
 * <b>nothing at all</b> — the version is not checked to exist, because the redirect target answers
 * that itself and asking twice would cost the reader a round trip to be told the same thing.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class PublishedVersionReadingIT {

  static final String CATEGORY = "reading";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY = "A version published a second ago is the one a reader lands on";

  static final String SLUG = Slugs.slug(STORY);

  /** The bundle's entry point — the one file whose cache header is not {@code immutable}. */
  private static final String INDEX = "index.html";

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      A release of the component library publishes its Storybook workbench into qits-artifacts, and
      somebody opens it. That is the whole product: `docs.<env>.<domain>/docs/@qits/ui-components`
      and you are looking at the newest release's workbench.

      `latest` is a QUERY, not a pointer. There is no alias table here, no `latest` tag and nothing
      to keep in step: the newest version is the first element of qits-artifacts' own version list,
      read on every request. So the list read before the publish and the one after it are the same
      request to the same address, and the answer changed because the store's rows did. A redeploy
      of this service loses nothing, because there was nothing to lose.

      Then two redirects, and they are not the same redirect twice.

      `/docs/@qits/ui-components` is the HUMAN entry point and lands on the reader — the client at
      `/read/<site>`, which has the version picker on it. It used to redirect straight to the newest
      bundle directory, which served a full-viewport Storybook with no rail: opening a doc took the
      version picker away, and the one URL a person would paste was the one that could not switch
      version. It still costs one store read, because a 404 for a site nothing is published under
      is worth answering before a page load rather than inside one.

      `/docs/@qits/ui-components/-/2026.830.0` redirects to the same path with a TRAILING SLASH,
      and that slash is load-bearing rather than tidy. A documentation bundle refers to its own
      assets relatively — Storybook emits `./assets/…`, which is what makes a bundle
      location-independent and publishable as an artifact at all — so the browser has to end up on
      a DIRECTORY url, or the page loads and is then blank with every asset 404ing one level too
      high. It is 302 and not 301 for the same reason `latest` is a query: what `/docs/<site>/`
      means changes with every release. This one asks the store NOTHING.

      Both files then come through byte for byte, with the upstream's content type and ETag
      untouched — and with the ONE header this service replaces rather than forwards. Every URL
      here is version-addressed, so its bytes can never change, but `index.html` is what a `latest`
      redirect lands on: a browser holding it for a year would keep rendering an old release's
      entry point after following a redirect to a new one. So the entry point revalidates and
      everything the bundle references is immutable, and only this service knows which of the two
      URLs the reader is on.
      """)
  @UserflowRunsAfter(DocsReadingBootstrapIT.class)
  void aFreshReleaseIsReadEndToEnd(Interactions story) {
    NetworkCapture.actor(StoryTarget.READER);

    // (a) what the site holds before the release lands.
    given()
        .queryParam("site", StoryStore.UI_SITE)
        .get(StoryTarget.VERSIONS_PATH)
        .then()
        .statusCode(200)
        .body("versions", hasSize(3))
        .body("versions[0].version", equalTo(StoryStore.UI_NEWEST_SEEDED));
    story
        .note(
            "the workbench has three published releases, and the newest of them is 2026.829.0 —"
                + " which is not a pointer anybody stored, just the first row the store answered"
                + " with")
        .as("before-the-release");

    // (b) the release publishes. A real PUT onto the store's docs plane, from outside this process
    // — which is what makes the next read a claim about the store's rows rather than about a
    // fixture that was always going to say this.
    assertEquals(
        201,
        StoryStore.publish(
            StoryStore.UI_SITE,
            StoryStore.UI_FRESH,
            "qits-spa-ui-components",
            StoryStore.MAIN_BRANCH,
            StoryStore.storybookBundle()),
        "the pipeline must have published the bundle");
    story
        .note(
            "a release publishes 2026.830.0 into qits-artifacts — a PUT onto the docs plane, with"
                + " the branch and the commit riding as artifacts metadata")
        .as("the-release-is-published");

    // (c) the same question, the same address, a different answer. Nothing here was invalidated.
    given()
        .queryParam("site", StoryStore.UI_SITE)
        .get(StoryTarget.VERSIONS_PATH)
        .then()
        .statusCode(200)
        .body("versions", hasSize(4))
        .body("versions[0].version", equalTo(StoryStore.UI_FRESH));
    story
        .note(
            "asked again, the newest is 2026.830.0 — the same request to the same address, and the"
                + " only thing that changed is the store's rows. There was no cache to invalidate"
                + " and no pointer to move")
        .as("published-is-already-latest");

    // (d) the human entry point. Redirects are NOT followed: what the reader's browser is told is
    // the whole of this route, and following it would draw an arrow to a client that is not built
    // in this run.
    StoryTarget.browser()
        .get(StoryTarget.sitePath(StoryStore.UI_SITE))
        .then()
        .statusCode(302)
        .header("Location", equalTo(StoryTarget.readerPath(StoryStore.UI_SITE)))
        // A redirect whose target changes with every release must never be held.
        .header("Cache-Control", equalTo("no-store"));

    long readsAfterEntry = StoryStore.reads(StoryStore.sitePath(StoryStore.UI_SITE));
    assertEquals(
        3,
        readsAfterEntry,
        "the entry point checks the site exists — two list reads plus this one");

    story
        .note(
            "opening /docs/@qits/ui-components lands on the READER at /read/@qits/ui-components,"
                + " not on the bundle: the shell is where the version picker lives, and the one url"
                + " a person pastes must not be the one that cannot switch version")
        .as("entry-point-redirects-to-the-reader");

    // (e) the version root. One slash, and no store read at all.
    StoryTarget.browser()
        .get(StoryTarget.versionRoot(StoryStore.UI_SITE, StoryStore.UI_FRESH))
        .then()
        .statusCode(302)
        .header(
            "Location", equalTo(StoryTarget.versionIndex(StoryStore.UI_SITE, StoryStore.UI_FRESH)));
    assertEquals(
        0,
        StoryStore.reads(StoryStore.versionPath(StoryStore.UI_SITE, StoryStore.UI_FRESH)),
        "the trailing-slash redirect must not check the version exists");

    story
        .note(
            "the version root answers 302 to the same path with a trailing slash, and asks the"
                + " store nothing — a bundle's assets are relative, so the browser has to end up on"
                + " a directory url, and whether the version exists is a question the target"
                + " answers by itself")
        .as("the-slash-is-load-bearing");

    // (f) the entry point's bytes, with the header this service decides.
    StoryTarget.browser()
        .get(StoryTarget.versionIndex(StoryStore.UI_SITE, StoryStore.UI_FRESH))
        .then()
        .statusCode(200)
        .contentType(startsWith("text/html"))
        .body(containsString("storybook-root"))
        // Passed through unchanged: the store's own validator.
        .header("ETag", equalTo(StoryStore.etag(StoryStore.UI_FRESH, INDEX)))
        // REPLACED, not forwarded: only this service knows which url the reader is on.
        .header("Cache-Control", equalTo("public, max-age=0, must-revalidate"));

    story
        .note(
            "the bundle's index.html comes through with the store's content type and ETag untouched"
                + " — and with a Cache-Control this service wrote: it revalidates, because it is"
                + " what a `latest` redirect lands on")
        .as("the-entry-point-revalidates");

    // (g) one asset beside it, and the other half of the cache decision.
    StoryTarget.browser()
        .get(StoryTarget.filePath(StoryStore.UI_SITE, StoryStore.UI_FRESH, StoryStore.UI_ASSET))
        .then()
        .statusCode(200)
        .contentType(startsWith("text/javascript"))
        .body(containsString("workbench"))
        .header("ETag", equalTo(StoryStore.etag(StoryStore.UI_FRESH, StoryStore.UI_ASSET)))
        .header("Cache-Control", equalTo("public, max-age=31536000, immutable"));

    story
        .note(
            "everything the bundle references is immutable for a year: the url is"
                + " version-addressed, so those bytes can never change — which is the same fact"
                + " that makes the entry point's revalidation the only exception it needs")
        .as("everything-else-is-immutable");
  }

  @AfterAll
  static void theReadingStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    // --- the near side: what the reader asked, and what it was told ------------------------------
    // Two list reads, one edge: the query never reaches a label, and both answered 200.
    edge(StoryTarget.READER, StoryTarget.SERVICE, StoryTarget.read(StoryTarget.VERSIONS_PATH, 200));
    edge(
        StoryTarget.READER,
        StoryTarget.SERVICE,
        StoryTarget.read(StoryTarget.sitePath(StoryStore.UI_SITE), 302));
    edge(
        StoryTarget.READER,
        StoryTarget.SERVICE,
        StoryTarget.read(StoryTarget.versionRoot(StoryStore.UI_SITE, StoryStore.UI_FRESH), 302));
    edge(
        StoryTarget.READER,
        StoryTarget.SERVICE,
        StoryTarget.read(StoryTarget.versionIndex(StoryStore.UI_SITE, StoryStore.UI_FRESH), 200));
    edge(
        StoryTarget.READER,
        StoryTarget.SERVICE,
        StoryTarget.read(
            StoryTarget.filePath(StoryStore.UI_SITE, StoryStore.UI_FRESH, StoryStore.UI_ASSET),
            200));

    // --- the far side: the publish, and the three reads it made possible -------------------------
    // The publish is drawn from the PIPELINE, not from qits-docs: this service has no write path
    // onto the docs plane at all, so a write recorded there can only be somebody else's.
    edge(
        StoryStore.PUBLISHER,
        StoryStore.SERVICE_NAME,
        StoryStore.published(StoryStore.versionPath(StoryStore.UI_SITE, StoryStore.UI_FRESH)));
    // Three reads of the version list — before the publish, after it, and behind the entry-point
    // redirect — and they are ONE edge, because they are the same request. The count that tells
    // them apart is the assertion in the story; the diagram's job is the dependency.
    edge(
        StoryTarget.SERVICE,
        StoryStore.SERVICE_NAME,
        StoryStore.read(StoryStore.sitePath(StoryStore.UI_SITE)));
    edge(
        StoryTarget.SERVICE,
        StoryStore.SERVICE_NAME,
        StoryStore.read(StoryStore.filePath(StoryStore.UI_SITE, StoryStore.UI_FRESH, INDEX)));
    edge(
        StoryTarget.SERVICE,
        StoryStore.SERVICE_NAME,
        StoryStore.read(
            StoryStore.filePath(StoryStore.UI_SITE, StoryStore.UI_FRESH, StoryStore.UI_ASSET)));

    // NINE, and the absence inside that number is the version root: it drew an incoming arrow and
    // no outgoing one. A tenth edge would be a store read nobody meant to make.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 9);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        SLUG,
        List.of(StoryTarget.READER, StoryTarget.SERVICE, StoryStore.PUBLISHER));

    for (String step :
        List.of(
            "before-the-release",
            "the-release-is-published",
            "published-is-already-latest",
            "entry-point-redirects-to-the-reader",
            "the-slash-is-load-bearing",
            "the-entry-point-revalidates",
            "everything-else-is-immutable")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, step);
    }
  }

  private static void edge(String from, String to, String label) {
    ReportAssertions.assertEdge(CATEGORY_SLUG, SLUG, NetworkEdge.HTTP, from, to, label);
  }
}
