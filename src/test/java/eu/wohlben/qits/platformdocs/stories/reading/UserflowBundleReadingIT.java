package eu.wohlben.qits.platformdocs.stories.reading;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
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
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>The story that reads itself.</b> A userflows bundle is a documentation site like any other,
 * and the one this pipeline publishes is a site in the very store this service reads — so a reader
 * following this story arrives at this story.
 *
 * <p>What makes it worth its own diagram rather than being a second Storybook is the {@code
 * metadata}, and what the client does with it. A released bundle is addressed by a version somebody
 * chose; a userflows bundle is addressed by the <b>commit sha</b> that produced it, with the
 * branch, the commit and the repository riding as the store's own metadata — which is what makes
 * "the docs for the branch I am on" answerable at all (see the branch story beside this one).
 * qits-docs passes that object through <b>verbatim</b>: parsed only far enough to prove it is JSON,
 * never rebuilt member by member, so a key the store learns tomorrow reaches the client with no
 * edit here.
 *
 * <p>And the {@code files} array is what a client reads a bundle <i>by</i>. A userflows bundle is
 * not a single-page app with an entry point and hashed assets; it is one directory per story, and
 * the client decides how to frame it from what is in there. Enumerating the files is what lets it
 * decide without probing for paths it would have to guess.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class UserflowBundleReadingIT {

  static final String CATEGORY = "reading";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY = "The stories this run publishes are read back through the same door";

  static final String SLUG = Slugs.slug(STORY);

  /**
   * The bundle this story publishes describes <b>this run's own catalogue</b>: its story directory
   * is the catalog story's category and slug, taken from the class that owns them rather than
   * spelled again, so the fixture cannot drift away from the bundle the pipeline really uploads.
   */
  private static final String STORY_DIRECTORY =
      DocsReadingBootstrapIT.CATEGORY_SLUG + "/" + DocsReadingBootstrapIT.CATALOG_SLUG;

  private static final String SIDECAR = STORY_DIRECTORY + "/userflow.json";

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      Every commit of every qits repository publishes its user stories as a docs site under the
      `@userflows` scope, version-addressed by the commit sha. This one included: the bundle read
      below is the shape of the bundle this very run uploads, and a reader who follows it ends up
      looking at the story they are reading.

      The version document is the thing a client actually needs, and it is the store's, passed
      through unedited. `files` enumerates the bundle — one directory per story, a `user-story.md`
      and a `userflow.json` in each — so the client can decide how to frame it (a site with an
      `index.html` framed whole, a directory of markdown rendered page by page, an OpenAPI document
      handed to swagger-ui) and knows what it may fetch without guessing a path. `metadata` carries
      the store's own DOTTED keys — `git.branch.name`, `git.commit.hash`, `git.repository.name` —
      and they arrive spelled exactly as the publisher wrote them, because this service parses that
      object only far enough to prove it is JSON and never rebuilds it member by member. A member
      the store adds tomorrow reaches the client with no edit here.

      Then the bundle wire, twice: the site index the suite writes after every story, and one
      story's sidecar three segments deep. Both are version-addressed and both come back immutable
      — the same rule the Storybook release gets, because it is a rule about the URL and not about
      the generator.

      The commit sha is why this diagram's paths read `{digest}`. Forty hex characters as a whole
      path segment is a value that can only have been generated, so the label is a template and the
      story's networkHash does not move with every publish — while the CalVer version in the
      release story beside it survives verbatim, because a person types that one.
      """)
  @UserflowRunsAfter(DocsReadingBootstrapIT.class)
  void aUserflowBundleIsReadLikeAnyOtherSite(Interactions story) {
    NetworkCapture.actor(StoryTarget.READER);

    // The pipeline publishes this run's stories, addressed by the commit that produced them.
    assertEquals(
        201,
        StoryStore.publish(
            StoryStore.DOCS_USERFLOWS,
            StoryStore.DOCS_USERFLOWS_VERSION,
            "qits-docs",
            StoryStore.STORY_BRANCH,
            StoryStore.userflowsBundle(
                DocsReadingBootstrapIT.CATEGORY_SLUG, DocsReadingBootstrapIT.CATALOG_SLUG)),
        "the pipeline must have published this run's stories");
    story
        .note(
            "the run's own user stories are published as the docs site @userflows/qits-docs,"
                + " version-addressed by the commit sha, with the branch and the repository riding"
                + " as artifacts metadata")
        .as("the-stories-are-published");

    // The version document, passed through rather than rebuilt. The dotted keys are read off an
    // extracted map: a path expression would have to quote them, and quoting them here would hide
    // that they reached the reader unedited.
    Map<String, String> metadata =
        given()
            .queryParam("site", StoryStore.DOCS_USERFLOWS)
            .queryParam("version", StoryStore.DOCS_USERFLOWS_VERSION)
            .get(StoryTarget.VERSION_PATH)
            .then()
            .statusCode(200)
            .body("name", equalTo(StoryStore.DOCS_USERFLOWS))
            .body("version", equalTo(StoryStore.DOCS_USERFLOWS_VERSION))
            .body("fileCount", equalTo(4))
            .body("files[0]", equalTo("index.html"))
            .body("files", hasItem(STORY_DIRECTORY + "/user-story.md"))
            .body("files", hasItem(SIDECAR))
            .extract()
            .path("metadata");
    assertEquals(
        StoryStore.STORY_BRANCH,
        metadata.get("git.branch.name"),
        "the store's dotted metadata keys must reach the reader unedited");
    assertEquals(StoryStore.DOCS_USERFLOWS_VERSION, metadata.get("git.commit.hash"));
    assertEquals("qits-docs", metadata.get("git.repository.name"));

    story
        .note(
            "one version's whole document comes back the store's: `files` enumerates every story"
                + " directory in the bundle, so the client never has to probe for a path it would"
                + " have to guess")
        .as("the-bundle-describes-itself");
    story
        .note(
            "and the dotted metadata keys — git.branch.name, git.commit.hash, git.repository.name —"
                + " arrive spelled as the publisher wrote them: parsed only far enough to prove it"
                + " is JSON, never rebuilt member by member")
        .as("metadata-passes-through-verbatim");

    // The bundle wire. The site index the suite rewrites after every story emit.
    StoryTarget.browser()
        .get(StoryTarget.versionIndex(StoryStore.DOCS_USERFLOWS, StoryStore.DOCS_USERFLOWS_VERSION))
        .then()
        .statusCode(200)
        .contentType(startsWith("text/html"))
        .body(containsString("user stories"))
        .header("Cache-Control", equalTo("public, max-age=0, must-revalidate"));

    // …and one story's own sidecar, three segments deep inside it.
    StoryTarget.browser()
        .get(
            StoryTarget.filePath(
                StoryStore.DOCS_USERFLOWS, StoryStore.DOCS_USERFLOWS_VERSION, SIDECAR))
        .then()
        .statusCode(200)
        .contentType(startsWith("application/json"))
        .body("slug", equalTo(DocsReadingBootstrapIT.CATALOG_SLUG))
        .header("Cache-Control", equalTo("public, max-age=31536000, immutable"));

    story
        .note(
            "the bundle reads like any other: its index at the version root, and one story's"
                + " userflow.json three segments deep — the canonical sidecar every other rendering"
                + " of that story is derived from")
        .as("a-story-reads-its-own-sidecar");
  }

  @AfterAll
  static void theUserflowBundleStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    edge(StoryTarget.READER, StoryTarget.SERVICE, StoryTarget.read(StoryTarget.VERSION_PATH, 200));
    edge(
        StoryTarget.READER,
        StoryTarget.SERVICE,
        StoryTarget.read(
            StoryTarget.versionIndex(StoryStore.DOCS_USERFLOWS, StoryStore.DOCS_USERFLOWS_VERSION),
            200));
    edge(
        StoryTarget.READER,
        StoryTarget.SERVICE,
        StoryTarget.read(
            StoryTarget.filePath(
                StoryStore.DOCS_USERFLOWS, StoryStore.DOCS_USERFLOWS_VERSION, SIDECAR),
            200));

    edge(
        StoryStore.PUBLISHER,
        StoryStore.SERVICE_NAME,
        StoryStore.published(
            StoryStore.versionPath(StoryStore.DOCS_USERFLOWS, StoryStore.DOCS_USERFLOWS_VERSION)));
    edge(
        StoryTarget.SERVICE,
        StoryStore.SERVICE_NAME,
        StoryStore.read(
            StoryStore.versionPath(StoryStore.DOCS_USERFLOWS, StoryStore.DOCS_USERFLOWS_VERSION)));
    edge(
        StoryTarget.SERVICE,
        StoryStore.SERVICE_NAME,
        StoryStore.read(
            StoryStore.filePath(
                StoryStore.DOCS_USERFLOWS, StoryStore.DOCS_USERFLOWS_VERSION, "index.html")));
    edge(
        StoryTarget.SERVICE,
        StoryStore.SERVICE_NAME,
        StoryStore.read(
            StoryStore.filePath(
                StoryStore.DOCS_USERFLOWS, StoryStore.DOCS_USERFLOWS_VERSION, SIDECAR)));

    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 7);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        SLUG,
        List.of(StoryTarget.READER, StoryTarget.SERVICE, StoryStore.PUBLISHER));

    for (String step :
        List.of(
            "the-stories-are-published",
            "the-bundle-describes-itself",
            "metadata-passes-through-verbatim",
            "a-story-reads-its-own-sidecar")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, step);
    }
  }

  private static void edge(String from, String to, String label) {
    ReportAssertions.assertEdge(CATEGORY_SLUG, SLUG, NetworkEdge.HTTP, from, to, label);
  }
}
