package eu.wohlben.qits.platformdocs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two pure pieces of the branch-discovery feature, tested without HTTP (this repo's suite is
 * deliberately plain JUnit — see {@code DocsPathsTest}): the tolerant version parse, which must run
 * unchanged against a pre-metadata qits-artifacts, and the branch query encoding, which takes a
 * reader-supplied value the site-path concatenation rule does not cover.
 */
class DocsUpstreamParseTest {

  @Test
  void aVersionWithMetadataCarriesItVerbatimAndOneWithoutParsesToNull() {
    List<DocsUpstream.Version> versions =
        DocsUpstream.parseVersionList(
            """
            {"name":"@userflows/qits-githost","versions":[
              {"version":"aaaa","fileCount":2,"totalBytes":10,"publishedAt":"2026-08-27T00:00:00Z",
               "metadata":{"git.branch.name":"main","git.commit.hash":"aaaa"}},
              {"version":"1.0.0","fileCount":3,"totalBytes":20,"publishedAt":"2026-08-01T00:00:00Z"}
            ]}
            """);
    assertEquals(2, versions.size());
    assertEquals("main", versions.get(0).metadata().getString("git.branch.name"));
    assertNull(versions.get(1).metadata(), "a pre-metadata version parses with no metadata");
    assertEquals("1.0.0", versions.get(1).version());
  }

  @Test
  void anEmptyListAndAMissingArrayBothParseToNoVersions() {
    assertEquals(List.of(), DocsUpstream.parseVersionList("{\"name\":\"x\",\"versions\":[]}"));
    assertEquals(List.of(), DocsUpstream.parseVersionList("{\"name\":\"x\"}"));
  }

  @Test
  void malformedJsonSurfacesRatherThanParsingToSomething() {
    assertThrows(RuntimeException.class, () -> DocsUpstream.parseVersionList("not json"));
  }

  @Test
  void theBranchQueryPercentEncodesWhatABranchNameMayCarry() {
    assertEquals("?meta.git.branch.name=main", DocsUpstream.branchQuery("main"));
    assertEquals(
        "?meta.git.branch.name=environment%2Fdev", DocsUpstream.branchQuery("environment/dev"));
    assertEquals(
        "?meta.git.branch.name=a+b%23c",
        DocsUpstream.branchQuery("a b#c"), "spaces and fragments must not reach URI.create raw");
  }
}
