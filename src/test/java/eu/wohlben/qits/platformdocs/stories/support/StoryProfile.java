package eu.wohlben.qits.platformdocs.stories.support;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * <b>One launched qits-docs for the whole story catalogue</b>, and every seam a story moves,
 * declared once.
 *
 * <p>A {@code @TestProfile} is what failsafe launches a process for, so two profiles would be two
 * qits-docs — two boots, two stores, and a diagram whose traffic landed in whichever process
 * happened to be running. Every story class therefore names this one, {@code
 * DocsReadingBootstrapIT} included: it is a story class like the others and it owns the boot.
 *
 * <h2>There is strikingly little here, and that is this service's shape</h2>
 *
 * <p>qits-docs holds no state: no datasource, no Flyway, no cache, no data directory to redirect
 * into {@code target/}. {@code .config/qits/deployments.yml} declares no {@code resources:} at all,
 * so there are no generic triples to supply, and the whole of a qits-docs deployment is <b>one
 * address</b>. That address is the one seam a story moves.
 *
 * <p>It also has <b>no authentication</b> — no oidc tenant, no machine gate, no forward-auth
 * identity. The edge routes {@code /docs/**} here from every host and a documentation site is
 * readable by whoever can reach it. So there are no bearers in this catalogue, nothing to assert
 * unleaked, and every incoming edge is drawn from {@code "a reader"} because that is genuinely all
 * the wire says about who is asking. The refusal stories here are the store's refusals, not this
 * service's.
 *
 * <h2>Both keys are RUNTIME keys</h2>
 *
 * <p>A packaged process takes its configuration as {@code -D} arguments on a jar that was already
 * built, so a build-time key here would be silently ignored and the stories would prove something
 * other than what they say.
 *
 * <ul>
 *   <li><b>{@code qits.docs.artifacts-url}</b> — {@link StoryStore}, a real listener on loopback
 *       speaking qits-artifacts' docs plane, <b>including the repository segment</b>, exactly as a
 *       deployment spells it. It starts here, before the application, and parks its port in a
 *       system property; that is also how a story method's {@code StoryStore.baseUrl()} reaches the
 *       very server the launched process reads from.
 *   <li><b>{@code quarkus.otel.sdk.disabled}</b> — dark outside a deployment, like {@code %dev} and
 *       {@code %test}. See the gap below.
 * </ul>
 *
 * <h2>One thing is OFF, and it is a stated coverage gap</h2>
 *
 * <p><b>The OTLP exporter.</b> qits-docs ships {@code quarkus-opentelemetry} pointed at
 * qits-observability, and it is the only dial-out this process has besides the store. It is
 * disabled here for the reason {@code %dev} and {@code %test} disable it — a suite must dial
 * nothing — and for a second reason that is about the diagrams: an exporter flushes on a schedule
 * of its own, on its own thread, so a span batch would draw an arrow into whichever story happened
 * to be open, which is a {@code networkHash} that never settles. <b>No story here covers the span
 * export</b>, and no story here claims the absence of one either: an {@code assertNoEdgesTo} over a
 * receiver that was never configured would be a claim about this profile rather than about the
 * service.
 */
public class StoryProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    // The store starts HERE, before the application, and parks its port in a system property: a
    // test profile is instantiated in more than one classloader, and the property table is the one
    // thing every copy (and a story method's own reads) shares.
    String store = StoryStore.ensureStarted();
    return Map.of(
        "qits.docs.artifacts-url",
        store + StoryStore.REPOSITORY_PATH,
        "quarkus.otel.sdk.disabled",
        "true");
  }
}
