package eu.wohlben.qits.platformdocs.stories.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.userflows.Labels;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * <b>qits-artifacts' docs plane</b> — the one thing qits-docs talks to — as an in-JVM stand-in,
 * plus the <b>outgoing</b> tap that draws what the launched process asked it.
 *
 * <h2>Why this is a stand-in of one API rather than a generic mock</h2>
 *
 * <p>qits-docs holds no state at all: everything it answers, it answers on the store's authority.
 * So the store <i>is</i> the fixture, and the stories only mean something if the far side really
 * has the shape qits-artifacts has. This one does, in the four places a generic canned-JSON mock
 * cannot reach and where every story in this catalogue lives:
 *
 * <ul>
 *   <li><b>the {@code /-/} grammar</b>: a site name spans up to four path segments and usually
 *       starts with {@code @}, so a route here is <i>parsed</i> ({@code
 *       <site>/-/<version>[/<path>]}) rather than matched against an exact string;
 *   <li><b>the query</b>: {@code ?meta.git.branch.name=} is the store's own metadata filter, and
 *       the whole point of the branch story is that qits-docs <i>pushes it upstream</i> rather than
 *       filtering locally — which is unobservable unless the far side answers differently for it;
 *   <li><b>real bytes</b>: a bundle file has a content type and an {@code ETag}, and both are
 *       pass-through decisions this service makes on a route where it replaces {@code
 *       Cache-Control}. Answering everything as {@code application/json} would make the reading
 *       stories prove a store that does not exist;
 *   <li><b>the upload</b>: {@code PUT <root>/<site>/-/<version>} is how a bundle gets here at all,
 *       and "a version published a second ago is already the newest" is not a claim a pre-seeded
 *       fixture can make.
 * </ul>
 *
 * <p><b>One stated liberty.</b> The real upload takes a gzipped tarball and explodes it; this one
 * takes a JSON manifest of {@code path → content}. The URL, the {@code X-Artifacts-Meta-*} headers
 * and the 201 are faithful — those are what qits-docs and the publishing pipeline actually agree on
 * — and nothing in this service ever sees the request body, so the tarball is the one part of that
 * exchange no story here could observe.
 *
 * <h2>The recording, and who each edge is FROM</h2>
 *
 * <p>Every answered request is appended to a file as {@code METHOD TARGET STATUS} — before the
 * response is written, so a line is on disk by the time its effect is observable — and {@code
 * TARGET} carries the <b>query</b>. That is deliberate and it is the one place this catalogue
 * differs from its siblings' peer stubs: the shipped RestAssured tap drops the query on the way
 * <i>in</i> (so three differently-filtered reads are one incoming edge), which would leave the
 * branch filter invisible in both directions if the outgoing half dropped it too. It is safe to
 * keep: {@link Labels} rewrites a query value only where it can only have been generated (a uuid, a
 * long hex run), so an authored branch name survives verbatim — percent-encoding included, which is
 * itself the evidence that {@code DocsUpstream.branchQuery} encoded it.
 *
 * <p><b>{@code from} is decided by the method, and that is a fact about the system rather than a
 * convenience.</b> qits-docs only ever <i>reads</i> the docs plane — it has no write path at all —
 * so a {@code GET} recorded here can only be this service, and anything else can only be the
 * pipeline that publishes bundles. Two initiators, told apart by construction.
 *
 * <p><b>There is no floor</b>, unlike the naive shape of this pattern: the recording is wiped when
 * the stub starts and the stub starts inside this run, so everything in it belongs to this run. A
 * floor taken at the first {@code @BeforeAll} would swallow anything the launched process asked
 * before the first story — and the first story's {@code assertEdgeCount} is precisely the assertion
 * that <i>nothing</i> did, which a floor would turn from a claim into an artefact.
 *
 * <h2>Stateless per request, with two deliberate exceptions</h2>
 *
 * <p>The catalogue is state, and it has to be: a publish has to change what the next read answers,
 * which is the whole subject of two stories. It lives in this class's static tables, mutated only
 * over the wire (a real {@code PUT}), so the <b>classloader split does not reach it</b> — a test
 * profile is instantiated in more than one classloader, and only the copy that started the server
 * holds the tables. Everyone else asks over HTTP, which is what a store is.
 *
 * <p>The second is {@link #refuse} / {@link #hangUp}, and it is spelled as a <b>file</b> for the
 * reason the sibling repositories spell it as one: no story-controlled value reaches these paths in
 * a way an outage could key on — "the store is down tonight" is a property of the store, not of the
 * site somebody asked for — and the arming story lives in a different classloader from the server.
 * Written by the one story about an outage, in a {@code try}/{@code finally} that always clears it,
 * wiped again when the stub starts, and read fresh on every request.
 */
public final class StoryStore {

  private static final java.nio.charset.Charset UTF_8 = StandardCharsets.UTF_8;

  /** How every diagram in this catalogue names the far side. */
  public static final String SERVICE_NAME = "qits-artifacts";

  /**
   * Who publishes. qits-docs has no write path onto the docs plane, so a non-{@code GET} recorded
   * here is by construction somebody else's — the CI step that PUTs a bundle.
   */
  public static final String PUBLISHER = "the publishing pipeline";

  /**
   * The docs repository's path on the store, repository segment included — the tail of the real
   * {@code qits.docs.artifacts-url} ({@code http://dev-qits-artifacts:8080/artifacts/docs/docs}),
   * kept verbatim so every path below is the URL a deployment really builds.
   */
  public static final String REPOSITORY_PATH = "/artifacts/docs/docs";

  /** The store's own metadata filter, which qits-docs pushes a {@code ?branch=} upstream as. */
  public static final String BRANCH_FILTER = "meta.git.branch.name";

  /** What a refused route answers: the store is up and cannot serve. */
  public static final int REFUSED_STATUS = 503;

  /** What an edge's label says where the store accepted the connection and then said nothing. */
  public static final String NO_ANSWER = "no answer";

  // --- the sites the store already holds --------------------------------------------------------

  /**
   * A Storybook workbench, released under CalVer — the platform's own component library, and the
   * shape of docs bundle qits-docs was written for. Its versions are <b>authored</b> and survive a
   * label verbatim, which is right: {@code /docs/@qits/ui-components/-/2026.830.0/} is a URL a
   * person pastes.
   */
  public static final String UI_SITE = "@qits/ui-components";

  public static final String UI_OLDEST = "2026.807.0";
  public static final String UI_MIDDLE = "2026.815.0";
  public static final String UI_NEWEST_SEEDED = "2026.829.0";

  /** The release a story publishes while the store is running. Not seeded. */
  public static final String UI_FRESH = "2026.830.0";

  /**
   * An asset of that bundle. The content hash is <b>inside</b> the segment, so {@link Labels} does
   * not rewrite it and it has to be authored — see {@link StoryTarget}'s class javadoc.
   */
  public static final String UI_ASSET = "assets/main-a1b2c3d4.js";

  /**
   * A sibling's userflows bundle — the kind this very pipeline publishes. Version-addressed by the
   * commit sha, so a label carries {@code {digest}} rather than this run's bundle.
   */
  public static final String GITHOST_USERFLOWS = "@userflows/qits-githost";

  public static final String GITHOST_MAIN_VERSION = "c4b3a29180f1e2d3c4b5a69786f1d0c9b8a7e6d5";
  public static final String GITHOST_DEV_VERSION = "1e2d3c4b5a69786f1d0c9b8a7e6d5c4b3a29180f";

  /** A site with no scope at all: it must still be findable, which is a grouping claim. */
  public static final String UNSCOPED_SITE = "qits-docs";

  public static final String UNSCOPED_VERSION = "2026.827.163649";

  /** This repository's own userflows bundle — the one a story publishes and then reads back. */
  public static final String DOCS_USERFLOWS = "@userflows/qits-docs";

  public static final String DOCS_USERFLOWS_VERSION = "6f1d0c9b8a7e6d5c4b3a29180f1e2d3c4b5a6978";

  // --- branches, as they ride in a bundle's metadata --------------------------------------------

  public static final String MAIN_BRANCH = "main";

  /**
   * The environment tier's entry branch. It carries a <b>slash</b>, on purpose: {@code
   * DocsUpstream.branchQuery} percent-encodes a branch name because it is a query value off the
   * reader's own query string, and {@code environment%2Fdev} on the outgoing edge is the evidence
   * that it did.
   */
  public static final String DEV_BRANCH = "environment/dev";

  /** A branch nothing was ever published from — the filtered-to-nothing arm. */
  public static final String ABSENT_BRANCH = "no-such-branch";

  /** The branch this catalogue's own bundle is published from. */
  public static final String STORY_BRANCH = "userflows-chargs";

  // --- the two sites that only ever go wrong ----------------------------------------------------

  /** A site the store has never heard of: its 404 is what qits-docs must pass through. */
  public static final String UNKNOWN_SITE = "@userflows/qits-nowhere";

  /**
   * A site whose version document the store answers <b>200</b> with, and garbles — valid JSON that
   * is not the shape promised, which is the one failure a reader could mistake for content. Kept
   * out of the catalogue on purpose: it is a route that misbehaves, not a site anybody publishes.
   */
  public static final String MANGLED_SITE = "@userflows/qits-mangled";

  public static final String MANGLED_VERSION = "unreadable";

  private static final String MANGLED_BODY = "the docs repository is rebuilding";

  // --- the server -------------------------------------------------------------------------------

  private static final String PORT_PROPERTY = "qits.test.story-store.port";

  private static final String SOURCE_ID = "story-store";

  private static final Path ROOT = Path.of("target", "story-store");

  /** The recording: one line per answered request, the shape an access log has. */
  private static final Path ACCESS_LOG = ROOT.resolve("access.log");

  /** Which path prefixes answer {@link #REFUSED_STATUS} right now. */
  private static final Path REFUSALS = ROOT.resolve("refusals");

  /** Which path prefixes are accepted and then dropped without a byte. */
  private static final Path HANGUPS = ROOT.resolve("hangups");

  /** The status parked in the recording for a request that got no answer at all. */
  private static final int HUNG_UP = 0;

  private static final Object LOCK = new Object();

  /** site → its versions, newest first. The one piece of state a publish moves. */
  private static final Map<String, List<StoredVersion>> SITES = new TreeMap<>();

  private static boolean registered;

  private static int harvested;

  private static final List<NetworkEdge> EDGES = new ArrayList<>();

  private StoryStore() {}

  /** One published version, exactly as qits-artifacts describes one. */
  private record StoredVersion(
      String version, String publishedAt, Map<String, String> metadata, Map<String, String> files) {

    long totalBytes() {
      return files.values().stream().mapToLong(content -> content.getBytes(UTF_8).length).sum();
    }
  }

  /** One answer, assembled before anything is written so the recording carries the real status. */
  private record Answer(int status, String contentType, String etag, byte[] body) {}

  /**
   * Start the stub once per JVM and park its port, wiping whatever an earlier run left behind and
   * seeding the catalogue. Called from {@link StoryProfile}, which is the only place that knows the
   * store's address in time to hand it to the launched artifact.
   */
  public static synchronized String ensureStarted() {
    String port = System.getProperty(PORT_PROPERTY);
    if (port != null) {
      return baseUrl(Integer.parseInt(port));
    }
    wipe();
    seed();
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException("could not start the story store stub", e);
    }
    server.createContext("/", StoryStore::handle);
    server.start();
    System.setProperty(PORT_PROPERTY, String.valueOf(server.getAddress().getPort()));
    return baseUrl(server.getAddress().getPort());
  }

  /** The store's base url, which a story needs to publish into it. */
  public static String baseUrl() {
    String port = System.getProperty(PORT_PROPERTY);
    if (port == null) {
      throw new IllegalStateException("the story store was never started in this JVM");
    }
    return baseUrl(Integer.parseInt(port));
  }

  private static String baseUrl(int port) {
    return "http://localhost:" + port;
  }

  /**
   * What the store holds before any story runs.
   *
   * <p>Three sites, and every one of them earns its place in the catalog story: a scoped release
   * bundle, a scoped userflows bundle whose two versions come from two different branches, and an
   * <b>unscoped</b> name, which is the arm that proves a site with no scope is grouped rather than
   * hidden. The order they come out in is the store's own — by name — and the grouping qits-docs
   * does must preserve it.
   */
  private static void seed() {
    SITES.clear();
    put(
        UI_SITE,
        version(UI_NEWEST_SEEDED, "2026-08-29T07:00:00Z", MAIN_BRANCH, UI_SITE, storybook()),
        version(UI_MIDDLE, "2026-08-15T07:00:00Z", MAIN_BRANCH, UI_SITE, storybook()),
        version(UI_OLDEST, "2026-08-07T07:00:00Z", MAIN_BRANCH, UI_SITE, storybook()));
    put(
        GITHOST_USERFLOWS,
        version(
            GITHOST_MAIN_VERSION,
            "2026-08-28T18:20:00Z",
            MAIN_BRANCH,
            "qits-githost",
            userflows("mirroring", "a-push-reaches-the-mirror-and-nothing-else")),
        version(
            GITHOST_DEV_VERSION,
            "2026-08-26T11:05:00Z",
            DEV_BRANCH,
            "qits-githost",
            userflows("mirroring", "a-push-reaches-the-mirror-and-nothing-else")));
    put(
        UNSCOPED_SITE,
        version(UNSCOPED_VERSION, "2026-08-27T16:36:00Z", MAIN_BRANCH, UNSCOPED_SITE, storybook()));
  }

  private static void put(String site, StoredVersion... versions) {
    SITES.put(site, new ArrayList<>(List.of(versions)));
  }

  private static StoredVersion version(
      String version,
      String publishedAt,
      String branch,
      String repository,
      Map<String, String> files) {
    Map<String, String> metadata = new LinkedHashMap<>();
    // The store's own dotted keys, which qits-docs passes through verbatim and never reinterprets.
    metadata.put("git.branch.name", branch);
    metadata.put("git.commit.hash", version);
    metadata.put("git.repository.name", repository);
    return new StoredVersion(version, publishedAt, metadata, files);
  }

  /** The files a Storybook bundle has: an entry point and content-hashed assets beside it. */
  private static Map<String, String> storybook() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put(
        "index.html", "<!doctype html><title>ui-components</title><div id=\"storybook-root\">");
    files.put(UI_ASSET, "export const workbench = 'ui-components';\n");
    files.put("assets/theme-e5f60718.css", ":root{--qits-ink:#0b1020}\n");
    files.put("iframe.html", "<!doctype html><title>preview</title>");
    return files;
  }

  /** The files a userflows bundle has: a site index, and one directory per story. */
  private static Map<String, String> userflows(String category, String slug) {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("index.html", "<!doctype html><title>user stories</title><h1>user stories</h1>");
    files.put(category + "/" + slug + "/index.html", "<!doctype html><title>" + slug + "</title>");
    files.put(category + "/" + slug + "/user-story.md", "# " + slug + "\n\n## Network\n");
    files.put(
        category + "/" + slug + "/userflow.json",
        "{\n  \"slug\": \"" + slug + "\",\n  \"category\": \"" + category + "\"\n}\n");
    return files;
  }

  // --- publishing -------------------------------------------------------------------------------

  /**
   * Publish a bundle the way the CI step does: {@code PUT <root>/<site>/-/<version>} with the three
   * {@code X-Artifacts-Meta-*} headers, answered 201 (or 409 — a published version is immutable).
   *
   * <p>Over the wire on purpose. The story method and the running server are in different
   * classloaders, so a direct call would mutate a copy of the tables nothing serves from; a real
   * request is also the only thing that gets the publish into the <b>recording</b>, which is where
   * the edge comes from.
   *
   * <p><b>Not through RestAssured.</b> The shipped tap labels every RestAssured request {@code
   * <actor> -> qits-docs}, and this one does not go to qits-docs at all.
   */
  public static int publish(
      String site, String version, String repository, String branch, Map<String, String> files) {
    JsonObject manifest = new JsonObject();
    JsonObject contents = new JsonObject();
    files.forEach(contents::put);
    manifest.put("files", contents);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl() + versionPath(site, version)))
            .header("Content-Type", "application/json")
            .header("X-Artifacts-Meta-git.branch.name", branch)
            .header("X-Artifacts-Meta-git.commit.hash", version)
            .header("X-Artifacts-Meta-git.repository.name", repository)
            .PUT(HttpRequest.BodyPublishers.ofString(manifest.encode(), UTF_8))
            .build();
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    } catch (IOException e) {
      throw new UncheckedIOException("could not publish " + site + "@" + version, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted publishing " + site + "@" + version, e);
    }
  }

  /** The files a fresh Storybook release carries — the same shape the seeded ones have. */
  public static Map<String, String> storybookBundle() {
    return storybook();
  }

  /** The files this catalogue's own userflows bundle carries. */
  public static Map<String, String> userflowsBundle(String category, String slug) {
    return userflows(category, slug);
  }

  // --- the server side --------------------------------------------------------------------------

  private static void handle(HttpExchange exchange) throws IOException {
    URI uri = exchange.getRequestURI();
    String path = uri.getPath();
    String query = uri.getRawQuery();
    String method = exchange.getRequestMethod();
    String target = query == null ? path : path + "?" + query;

    if (isArmed(HANGUPS, path)) {
      // Recorded first: the request DID reach the store, and that is exactly what distinguishes
      // "could not be reached" from "answered something else".
      record(method, target, HUNG_UP);
      exchange.close();
      return;
    }
    Answer answer =
        isArmed(REFUSALS, path)
            ? json(REFUSED_STATUS, new JsonObject().put("message", path + " is unavailable"))
            : route(method, path, query, exchange);

    record(method, target, answer.status());
    if (answer.contentType() != null) {
      exchange.getResponseHeaders().set("Content-Type", answer.contentType());
    }
    if (answer.etag() != null) {
      exchange.getResponseHeaders().set("ETag", answer.etag());
    }
    exchange.sendResponseHeaders(answer.status(), answer.body().length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(answer.body());
    }
  }

  /** The docs plane's grammar, parsed rather than matched — see the class javadoc. */
  private static Answer route(String method, String path, String query, HttpExchange exchange)
      throws IOException {
    if (!path.startsWith(REPOSITORY_PATH)) {
      return notFound("not the docs repository: " + path);
    }
    String rest = path.substring(REPOSITORY_PATH.length());
    if (rest.isEmpty() || rest.equals("/")) {
      return "GET".equals(method) ? catalogue() : notFound("the catalog is read-only");
    }
    if (!rest.startsWith("/")) {
      return notFound("not the docs repository: " + path);
    }
    rest = rest.substring(1);

    int separator = rest.indexOf("/-/");
    if (separator < 0) {
      return "GET".equals(method) ? versions(rest, branchOf(query)) : notFound("no such route");
    }
    String site = rest.substring(0, separator);
    String tail = rest.substring(separator + "/-/".length());
    int slash = tail.indexOf('/');
    if (slash < 0) {
      return switch (method) {
        case "GET" -> document(site, tail);
        case "PUT" -> accept(site, tail, exchange);
        default -> notFound("no such route");
      };
    }
    return "GET".equals(method)
        ? file(site, tail.substring(0, slash), tail.substring(slash + 1))
        : notFound("no such route");
  }

  /** {@code GET <root>} — the flat catalog, ordered by name. Grouping is qits-docs' business. */
  private static Answer catalogue() {
    JsonArray sites = new JsonArray();
    synchronized (LOCK) {
      SITES.forEach(
          (name, versions) ->
              sites.add(
                  new JsonObject()
                      .put("name", name)
                      .put("versionCount", versions.size())
                      .put("latestVersion", versions.getFirst().version())
                      // Read by nobody in qits-docs' CatalogEntry — it is here so the parse runs
                      // against the real payload rather than a trimmed one.
                      .put("latestPublishedAt", versions.getFirst().publishedAt())));
    }
    return json(200, new JsonObject().put("sites", sites));
  }

  /**
   * {@code GET <root>/<site>[?meta.git.branch.name=<branch>]} — every version, newest first.
   *
   * <p>A known site filtered to nothing is an <b>answer</b> (200, empty list), never a 404: the
   * store knows the site, it just holds nothing from that branch. An unknown site is the 404.
   */
  private static Answer versions(String site, String branch) {
    List<StoredVersion> held = held(site);
    if (held == null) {
      return notFound("no such site: " + site);
    }
    JsonArray listed = new JsonArray();
    for (StoredVersion version : held) {
      if (branch != null && !branch.equals(version.metadata().get("git.branch.name"))) {
        continue;
      }
      listed.add(
          new JsonObject()
              .put("version", version.version())
              .put("fileCount", version.files().size())
              .put("totalBytes", version.totalBytes())
              .put("publishedAt", version.publishedAt())
              .put("metadata", metadata(version)));
    }
    return json(200, new JsonObject().put("name", site).put("versions", listed));
  }

  /**
   * {@code GET <root>/<site>/-/<version>} — one version's whole document, {@code files} included.
   */
  private static Answer document(String site, String version) {
    if (MANGLED_SITE.equals(site) && MANGLED_VERSION.equals(version)) {
      // 200 with valid JSON that is not the shape promised — the one failure a reader could
      // otherwise mistake for content. Serialised as a bare JSON string, quotes and all.
      return new Answer(
          200, "application/json", null, ("\"" + MANGLED_BODY + "\"").getBytes(UTF_8));
    }
    StoredVersion held = held(site, version);
    if (held == null) {
      return notFound("no such version: " + site + "@" + version);
    }
    JsonArray files = new JsonArray();
    held.files().keySet().forEach(files::add);
    return json(
        200,
        new JsonObject()
            .put("name", site)
            .put("version", held.version())
            .put("fileCount", held.files().size())
            .put("totalBytes", held.totalBytes())
            .put("publishedAt", held.publishedAt())
            .put("files", files)
            .put("metadata", metadata(held)));
  }

  /** A version's metadata as the store's own object — dotted keys, in insertion order. */
  private static JsonObject metadata(StoredVersion version) {
    JsonObject metadata = new JsonObject();
    version.metadata().forEach(metadata::put);
    return metadata;
  }

  /**
   * {@code GET <root>/<site>/-/<version>/<path>} — one file, with its own type and {@code ETag}.
   */
  private static Answer file(String site, String version, String path) {
    StoredVersion held = held(site, version);
    String content = held == null ? null : held.files().get(path);
    if (content == null) {
      return notFound("no such file: " + site + "@" + version + "/" + path);
    }
    // NO Cache-Control: whatever a reader ends up holding this file for is qits-docs' decision,
    // restated per URL, and a header passed through from here would hide that.
    return new Answer(200, contentType(path), etag(version, path), content.getBytes(UTF_8));
  }

  /** {@code PUT <root>/<site>/-/<version>} — the upload, answered 201, or 409 for a re-publish. */
  private static Answer accept(String site, String version, HttpExchange exchange)
      throws IOException {
    if (held(site, version) != null) {
      return json(409, new JsonObject().put("message", "already published"));
    }
    JsonObject manifest =
        new JsonObject(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
    JsonObject contents = manifest.getJsonObject("files", new JsonObject());
    Map<String, String> files = new LinkedHashMap<>();
    contents.forEach(entry -> files.put(entry.getKey(), String.valueOf(entry.getValue())));

    Map<String, String> metadata = new LinkedHashMap<>();
    exchange
        .getRequestHeaders()
        .forEach(
            (header, values) -> {
              // com.sun's Headers capitalises after every '-', so the key arrives as
              // `X-Artifacts-Meta-Git.branch.name`. The store's keys are lower-case dotted, so the
              // remainder is lower-cased back rather than trusted.
              String lower = header.toLowerCase(java.util.Locale.ROOT);
              if (lower.startsWith("x-artifacts-meta-") && !values.isEmpty()) {
                metadata.put(lower.substring("x-artifacts-meta-".length()), values.getFirst());
              }
            });
    synchronized (LOCK) {
      SITES
          .computeIfAbsent(site, name -> new ArrayList<>())
          .addFirst(new StoredVersion(version, PUBLISHED_AT, metadata, files));
    }
    return json(
        201,
        new JsonObject().put("name", site).put("version", version).put("fileCount", files.size()));
  }

  /**
   * When an uploaded version says it was published — a constant, never the clock. Nothing this stub
   * answers may differ between two runs of the same story.
   */
  private static final String PUBLISHED_AT = "2026-08-30T09:15:00Z";

  private static List<StoredVersion> held(String site) {
    synchronized (LOCK) {
      List<StoredVersion> versions = SITES.get(site);
      return versions == null ? null : List.copyOf(versions);
    }
  }

  private static StoredVersion held(String site, String version) {
    List<StoredVersion> versions = held(site);
    if (versions == null) {
      return null;
    }
    return versions.stream()
        .filter(held -> held.version().equals(version))
        .findFirst()
        .orElse(null);
  }

  /** The branch a {@code ?meta.git.branch.name=} query asks for, decoded; null when unfiltered. */
  private static String branchOf(String rawQuery) {
    if (rawQuery == null) {
      return null;
    }
    for (String pair : rawQuery.split("&")) {
      int equals = pair.indexOf('=');
      if (equals > 0 && BRANCH_FILTER.equals(pair.substring(0, equals))) {
        return URLDecoder.decode(pair.substring(equals + 1), UTF_8);
      }
    }
    return null;
  }

  private static Answer json(int status, JsonObject body) {
    return new Answer(status, "application/json", null, body.encode().getBytes(UTF_8));
  }

  private static Answer notFound(String message) {
    return json(404, new JsonObject().put("message", message));
  }

  /** A bundle file's content type, by extension — what qits-docs passes through unchanged. */
  public static String contentType(String path) {
    if (path.endsWith(".html")) {
      return "text/html; charset=utf-8";
    }
    if (path.endsWith(".js")) {
      return "text/javascript; charset=utf-8";
    }
    if (path.endsWith(".css")) {
      return "text/css; charset=utf-8";
    }
    if (path.endsWith(".md")) {
      return "text/markdown; charset=utf-8";
    }
    if (path.endsWith(".json")) {
      return "application/json";
    }
    return "application/octet-stream";
  }

  /**
   * A bundle file's {@code ETag} — the header qits-docs passes through unchanged beside the {@code
   * Cache-Control} it replaces.
   *
   * <p>A <b>pure function</b> of the version and the path rather than a stored value, so a story
   * method can spell the expectation without asking the server for it: the tables live in the
   * classloader that started the stub, and this does not.
   */
  public static String etag(String version, String path) {
    return "\"" + Integer.toHexString((version + "/" + path).hashCode()) + "\"";
  }

  // --- the two armed faults ---------------------------------------------------------------------

  /**
   * Make every path starting with {@code prefix} answer {@link #REFUSED_STATUS} — the store is up
   * and cannot serve — until {@link #answerNormally()} is called.
   *
   * <p><b>Always in a {@code try}/{@code finally}.</b> An outage that outlived its story would be a
   * broken store in somebody else's diagram, and the two would look exactly alike.
   */
  public static void refuse(String prefix) {
    write(REFUSALS, prefix + "\n");
  }

  /**
   * Make every path starting with {@code prefix} be accepted and then dropped without a byte — the
   * other arm of {@code DocsUpstreamException}, which qits-docs reaches through an {@code
   * IOException} rather than through a status it read. Same discipline as {@link #refuse}.
   */
  public static void hangUp(String prefix) {
    write(HANGUPS, prefix + "\n");
  }

  /** Clear every armed fault. Idempotent, and safe to call when nothing was armed. */
  public static void answerNormally() {
    try {
      Files.deleteIfExists(REFUSALS);
      Files.deleteIfExists(HANGUPS);
    } catch (IOException e) {
      throw new UncheckedIOException("could not clear the armed faults", e);
    }
  }

  private static boolean isArmed(Path file, String path) {
    if (!Files.isRegularFile(file)) {
      return false;
    }
    String armed;
    try {
      armed = Files.readString(file, UTF_8);
    } catch (IOException unreadable) {
      return false;
    }
    for (String prefix : armed.split("\n")) {
      if (!prefix.isBlank() && path.startsWith(prefix.strip())) {
        return true;
      }
    }
    return false;
  }

  // --- what a story class calls -----------------------------------------------------------------

  /** Register the outgoing tap once per JVM. Called from every story class's {@code @BeforeAll}. */
  public static void install() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      harvested = 0;
      NetworkCapture.source(SOURCE_ID, StoryStore::edges);
      registered = true;
    }
  }

  /**
   * How many times the store was <b>read</b> at exactly {@code path} (any query, any status).
   *
   * <p>{@code GET} only, and that is not a detail: a story that publishes a bundle and then asks
   * "was the version checked?" would otherwise count its own {@code PUT} to the same address.
   */
  public static long reads(String path) {
    return targets("GET")
        .map(target -> target.contains("?") ? target.substring(0, target.indexOf('?')) : target)
        .filter(path::equals)
        .count();
  }

  /**
   * How many times the store was read at exactly {@code target}, <b>query included</b> — the only
   * way to say "the filter left the process", since a filtered and an unfiltered read are the same
   * path.
   */
  public static long readsMatching(String target) {
    return targets("GET").filter(target::equals).count();
  }

  private static java.util.stream.Stream<String> targets(String method) {
    return recordedLines().stream()
        .map(line -> line.split(" "))
        .filter(fields -> fields.length == 3 && fields[0].equals(method))
        .map(fields -> fields[1]);
  }

  // --- the paths and the labels an assertion has to spell ---------------------------------------

  /** {@code <root>/<site>} — where the versions of one site are asked for. */
  public static String sitePath(String site) {
    return REPOSITORY_PATH + "/" + site;
  }

  /** …with the store's own branch filter on it, percent-encoded exactly as qits-docs encodes it. */
  public static String sitePath(String site, String branch) {
    return sitePath(site) + "?" + BRANCH_FILTER + "=" + URLEncoder.encode(branch, UTF_8);
  }

  /** {@code <root>/<site>/-/<version>} — one version's document, and the upload's address. */
  public static String versionPath(String site, String version) {
    return sitePath(site) + "/-/" + version;
  }

  /** {@code <root>/<site>/-/<version>/<path>} — one file. */
  public static String filePath(String site, String version, String path) {
    return versionPath(site, version) + "/" + path;
  }

  /** The label an answered store call renders as, scrubbed exactly as the drain will scrub it. */
  public static String asked(String method, String target, String status) {
    return Labels.scrub(method + " " + target + " -> " + status);
  }

  /** {@code GET <target> -> <status>}. */
  public static String read(String target, int status) {
    return asked("GET", target, String.valueOf(status));
  }

  /** {@code GET <target> -> 200}. */
  public static String read(String target) {
    return read(target, 200);
  }

  /** {@code PUT <target> -> 201} — a bundle arriving, drawn from the pipeline. */
  public static String published(String target) {
    return asked("PUT", target, "201");
  }

  // --- the source -------------------------------------------------------------------------------

  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      harvest();
      return List.copyOf(EDGES);
    }
  }

  private static void harvest() {
    List<String> lines = recordedLines();
    if (harvested > lines.size()) {
      harvested = 0;
      lines = recordedLines();
    }
    for (String line : lines.subList(harvested, lines.size())) {
      edge(line).ifPresent(EDGES::add);
    }
    harvested = lines.size();
  }

  /**
   * One recorded line as an edge. {@code from} is decided by the method: qits-docs only reads the
   * docs plane, so a write can only be the publishing pipeline — see the class javadoc.
   */
  private static Optional<NetworkEdge> edge(String line) {
    // "METHOD TARGET STATUS" — three fields, no quoting, and a request target carries no raw space.
    String[] fields = line.strip().split(" ");
    if (fields.length != 3 || !fields[1].startsWith("/")) {
      return Optional.empty();
    }
    String method = fields[0];
    String status = String.valueOf(HUNG_UP).equals(fields[2]) ? NO_ANSWER : fields[2];
    return Optional.of(
        NetworkEdge.http(
            "GET".equals(method) ? StoryTarget.SERVICE : PUBLISHER,
            SERVICE_NAME,
            method + " " + fields[1] + " -> " + status));
  }

  /**
   * The recording's complete lines. A missing file is an empty recording rather than a failure, and
   * an <b>unterminated tail is dropped</b>: the server appends while this reads, and half a line
   * would shape half an edge. The next harvest sees it whole.
   */
  private static List<String> recordedLines() {
    if (!Files.isRegularFile(ACCESS_LOG)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(ACCESS_LOG, UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    return List.of(text.substring(0, lastComplete).split("\n"));
  }

  private static synchronized void record(String method, String target, int status) {
    try {
      Files.createDirectories(ROOT);
      Files.writeString(
          ACCESS_LOG,
          method + " " + target + " " + status + "\n",
          UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // A recording that cannot be written costs the diagram an arrow; it must not cost the
      // launched process its answer, which is what a reader is actually waiting for.
    }
  }

  private static void write(Path file, String content) {
    try {
      Files.createDirectories(ROOT);
      Files.writeString(file, content, UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + file, e);
    }
  }

  private static void wipe() {
    try {
      Files.deleteIfExists(ACCESS_LOG);
      Files.deleteIfExists(REFUSALS);
      Files.deleteIfExists(HANGUPS);
    } catch (IOException e) {
      throw new UncheckedIOException("could not clear " + ROOT, e);
    }
  }
}
