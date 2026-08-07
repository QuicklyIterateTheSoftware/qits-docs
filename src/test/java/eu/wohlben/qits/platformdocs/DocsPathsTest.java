package eu.wohlben.qits.platformdocs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The route grammar, as plain JUnit — no Quarkus, because this is a property of the regexes and the
 * cases that matter are cheap to be exhaustive about.
 *
 * <p>The load-bearing case is {@link #theReadingSpellingsDoNotOverlap}. Five routes share a prefix
 * and differ only by a trailing slash or by the {@code /-/} marker, so getting one wrong does not
 * produce an error — it produces a route that quietly answers for another, and the symptom is a
 * documentation page that redirects in a loop or serves an index where a file was asked for.
 */
class DocsPathsTest {

  @Test
  void aSiteNameMayBeScopedOrNested() {
    assertEquals(
        "@qits/ui-components", group(DocsPaths.SITE, "/platform-docs/@qits/ui-components", "name"));
    assertEquals("ui-components", group(DocsPaths.SITE, "/platform-docs/ui-components", "name"));
    assertEquals(
        "someproject/somelib", group(DocsPaths.SITE, "/platform-docs/someproject/somelib", "name"));
    assertFalse(
        matches(DocsPaths.SITE, "/platform-docs/a/b/c/d/e"), "five segments is past the cap");
  }

  @Test
  void aVersionPathSplitsIntoNameAndVersion() {
    String root = "/platform-docs/@qits/ui-components/-/2026.807.0";
    assertEquals("@qits/ui-components", group(DocsPaths.VERSION_ROOT, root, "name"));
    assertEquals("2026.807.0", group(DocsPaths.VERSION_ROOT, root, "version"));

    String file = root + "/assets/iframe-BPG5Eshk.js";
    assertEquals("2026.807.0", group(DocsPaths.FILE, file, "version"));
    assertEquals("assets/iframe-BPG5Eshk.js", group(DocsPaths.FILE, file, "path"));
  }

  @Test
  void theReadingSpellingsDoNotOverlap() {
    String site = "/platform-docs/@qits/ui-components";
    String siteSlash = site + "/";
    String root = site + "/-/2026.807.0";
    String rootSlash = root + "/";
    String file = rootSlash + "index.html";

    // Each of the five matches its own route and no other. Written as a full matrix rather than
    // spot checks, because what breaks here breaks as one route silently answering for another.
    assertTrue(matches(DocsPaths.SITE, site));
    assertFalse(matches(DocsPaths.SITE_LATEST, site), "no trailing slash");
    assertFalse(matches(DocsPaths.VERSION_ROOT, site));
    assertFalse(matches(DocsPaths.FILE, site));

    assertTrue(matches(DocsPaths.SITE_LATEST, siteSlash));
    assertFalse(matches(DocsPaths.SITE, siteSlash), "the slash is not part of a name");

    assertTrue(matches(DocsPaths.VERSION_ROOT, root));
    assertFalse(matches(DocsPaths.SITE, root), "a bare - is not a name segment");
    assertFalse(matches(DocsPaths.SITE_LATEST, root));
    assertFalse(matches(DocsPaths.VERSION_INDEX, root));
    assertFalse(matches(DocsPaths.FILE, root));

    assertTrue(matches(DocsPaths.VERSION_INDEX, rootSlash));
    assertFalse(matches(DocsPaths.VERSION_ROOT, rootSlash));
    assertFalse(matches(DocsPaths.FILE, rootSlash), "there is no file after the slash");

    assertTrue(matches(DocsPaths.FILE, file));
    assertFalse(matches(DocsPaths.VERSION_INDEX, file));
    assertFalse(matches(DocsPaths.VERSION_ROOT, file));
  }

  @Test
  void theMachineSurfaceIsNotASite() {
    // THE case this grammar would get wrong on its own. `q/health/ready` and `api/sites` are three
    // and two perfectly valid name segments, so without the reserved-prefix lookahead the site
    // routes swallow both — a readiness probe would resolve as a site called `q/health/ready`,
    // answer 404, and the deployment's health gate would never go green against a healthy process.
    assertFalse(matches(DocsPaths.SITE, "/platform-docs/q/health/ready"));
    assertFalse(matches(DocsPaths.SITE, "/platform-docs/q/health/live"));
    assertFalse(matches(DocsPaths.SITE_LATEST, "/platform-docs/q/metrics/"));
    assertFalse(matches(DocsPaths.SITE, "/platform-docs/api/sites"));
    assertFalse(matches(DocsPaths.SITE, "/platform-docs/read/@qits/ui-components"));
    assertFalse(matches(DocsPaths.FILE, "/platform-docs/read/x/-/1.0.0/i.html"));
    assertFalse(matches(DocsPaths.FILE, "/platform-docs/q/health/-/1.0.0/x"));

    // The reservation is exactly two prefixes and does not leak into ordinary names.
    assertTrue(matches(DocsPaths.SITE, "/platform-docs/quarkus-things"), "q is not a prefix of q/");
    assertTrue(matches(DocsPaths.SITE, "/platform-docs/apiary"));
    assertTrue(matches(DocsPaths.SITE, "/platform-docs/readme"), "read is not a prefix of read/");
    assertTrue(matches(DocsPaths.SITE, "/platform-docs/@qits/api"), "reserved only at the root");
  }

  @Test
  void everyGroupIsNamedOrNonCapturing() {
    // vertx-web compares Matcher.groupCount() against the named groups it scraped from the pattern
    // and falls back to positional param0..paramN when they disagree — so ONE bare (...) anywhere
    // in
    // these patterns breaks pathParam("name") on that route, at runtime, silently. This grammar is
    // the one most at risk of it: NAME nests a repeated group inside a named one.
    assertGroupsAllNamed(DocsPaths.SITE, 1);
    assertGroupsAllNamed(DocsPaths.SITE_LATEST, 1);
    assertGroupsAllNamed(DocsPaths.VERSION_ROOT, 2);
    assertGroupsAllNamed(DocsPaths.VERSION_INDEX, 2);
    assertGroupsAllNamed(DocsPaths.FILE, 3);
  }

  private static void assertGroupsAllNamed(String regex, int expectedNamed) {
    long named = Pattern.compile("\\(\\?<[a-zA-Z][a-zA-Z0-9]*>").matcher(regex).results().count();
    assertEquals(expectedNamed, named, "named group count changed in: " + regex);
    assertEquals(
        expectedNamed,
        Pattern.compile(regex).matcher("").groupCount(),
        "a bare capturing group crept into: " + regex);
  }

  private static boolean matches(String regex, String path) {
    return Pattern.compile(regex).matcher(path).matches();
  }

  private static String group(String regex, String path, String group) {
    Matcher matcher = Pattern.compile(regex).matcher(path);
    assertTrue(matcher.matches(), regex + " did not match " + path);
    return matcher.group(group);
  }
}
