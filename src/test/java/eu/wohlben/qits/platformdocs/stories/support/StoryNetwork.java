package eu.wohlben.qits.platformdocs.stories.support;

import eu.wohlben.qits.userflows.NetworkTaps;

/**
 * <b>Both ends of every diagram in this catalogue, wired in one call</b> — so a story class's
 * {@code @BeforeAll} is one line and no class can wire half of it.
 *
 * <p>There are two feeds, and they are two different mechanisms:
 *
 * <ul>
 *   <li><b>the near side</b>, {@link NetworkTaps#restAssured}: every request a story makes becomes
 *       {@code <actor> -> qits-docs}, labelled {@code METHOD <scrubbed path> -> <status>}. The
 *       framework ships it; this repository's hand-copied {@code StoryNetworkFilter} was deleted
 *       when these stories were written. It is idempotent per service, which is why every class may
 *       call this method. Its default skip — any path carrying a {@code /q/} segment — was checked
 *       against this service's own {@code quarkus.http.non-application-root-path}; see {@link
 *       StoryTarget}.
 *   <li><b>the far side</b>, {@link StoryStore#install()}: the store's access log, cumulative and
 *       with <b>no floor</b>, which is what lets the first story's edge count say that the launched
 *       process asked the store nothing at boot.
 * </ul>
 *
 * <p>There is no third feed and there could not be: qits-docs talks to exactly one thing. That is
 * the whole architecture — "the store answers what exists, this service answers what to read" — and
 * a diagram set in which every story has the same single far side is that sentence, drawn.
 *
 * <h2>Order is load-bearing, and it is the class names that carry it</h2>
 *
 * <p>A cumulative source is attributed by a cursor, so anything recorded before the first drain
 * lands in whichever story drains FIRST. {@code UserflowClassOrderer} sorts by fully-qualified
 * class name, so {@code …platformdocs.DocsReadingBootstrapIT} runs before every {@code
 * …platformdocs.stories.*} class and owns the boot; within {@code stories}, {@code reading} runs
 * before {@code refusals}. {@code @UserflowRunsAfter} states the ones that are real dependencies as
 * well as being true of the names — the catalog story reads the store <i>before</i> anything
 * publishes into it, and that is a dependency rather than a coincidence of spelling.
 */
public final class StoryNetwork {

  private StoryNetwork() {}

  /**
   * Install the near-side tap and register the far-side recording. Idempotent, and safe from any
   * story class's {@code @BeforeAll} — {@link eu.wohlben.qits.userflows.NetworkCapture#source}
   * replaces a supplier while keeping its cursor, so a class that runs second does not re-attribute
   * what the first drained.
   */
  public static void install() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StoryStore.install();
  }
}
