# qits-docs-service

**The environment's reading room**, deployed as the `qits-docs` application. One address where every
documentation site published into this environment can be read —
`docs.<env>.<domain>/docs/@qits/ui-components/` and you are looking at the newest release's
workbench.

An **environment service**: it is deployed once per environment and reads that environment's own
`qits-artifacts`. It was platform-scoped only because the store was.

A small, stateless Quarkus 3 (Java 25) application that compiles to a **GraalVM native binary**. It
has no database, no ORM, no cache and no state of its own: it resolves a readable URL to a
version-addressed one and streams the bytes qits-artifacts holds.

    ./mvnw verify                                  # no docker, no database, no qits-artifacts
    ./mvnw package && java -jar target/quarkus-app/quarkus-run.jar
    ./mvnw verify -Dnative && ./target/qits-docs

`verify` also launches the packaged artifact: every integration test here is a **userflow story**,
and the six of them are what emit `target/userstories/`, published per commit as the docs site
`@userflows/qits-docs` — into the store this service reads. They need no docker and no running
qits-artifacts (the far side is an in-process stand-in on loopback), but they do need **Maven** to
reach the platform's own repository for the one test-scope jar `qits-userflows`, which is the only
qits dependency this pom has. Off the platform network that means the usual pair:

    export QITS_MAVEN_REPOSITORY_URL=http://registry.dev.localhost:8080/artifacts/maven/maven
    ./mvnw -s .qits-maven-settings.xml verify

## Why this exists rather than a path on qits-artifacts

qits-artifacts is the byte plane: it holds a docs bundle exploded into content-addressed blobs and
serves any file of any published version at
`/artifacts/docs/docs/<site>/-/<version>/<path>`. That is a complete, correct API and a poor
address. It has no notion of "the newest version", no opinion about which file is a site's entry
point, and no reason to grow one — an artifact store that started redirecting readers would be
a store with a reading experience welded to it.

So the split is: **the store answers what exists, this service answers what to read.** Everything
this process adds is a redirect or a header, and the section below is the whole of it.

## The routes

| Route | What it does |
| --- | --- |
| `GET /docs/<site>` | 302 → the reader, at `/read/<site>` |
| `GET /docs/<site>/` | the same |
| `GET /docs/<site>/-/<version>` | 302 → the same path **with a trailing slash** |
| `GET /docs/<site>/-/<version>/` | the bundle's `index.html` |
| `GET /docs/<site>/-/<version>/<path>` | one file, streamed |
| `GET /docs/api/sites` | the catalog, grouped by scope |
| `GET /docs/api/versions?site=<name>` | one site's versions, newest first |

A `<site>` is whatever namespacing the publishing project already uses: `@qits/ui-components` where
there is an npm package, `someproject/somelib` where there is not, up to four segments deep. The
`/-/` between the name and the version is what makes a multi-segment name unambiguous, and it is
**npm's separator, reused deliberately** — `/artifacts/npm/<repo>/<pkg>/-/<file>` has had it all
along. The grammar is byte-for-byte the one qits-artifacts serves under `/artifacts/docs`, so a
version copied out of a release log resolves here by pasting a different prefix in front of it.

### The trailing slash is load-bearing

A documentation bundle refers to its own assets **relatively** — Storybook emits `./assets/…`, and
so does every other generator worth using, which is what makes a bundle location-independent and
therefore publishable as an artifact at all. The cost is that the browser must end up on a
**directory** URL. Serving content at `…/-/2026.807.0` rather than redirecting to
`…/-/2026.807.0/` gives a page that loads and is then blank, with every asset 404ing one level too
high — the least readable failure this service could have. Hence the redirect, and hence
`DocsPathsTest`'s full five-route matrix.

### `latest` is a query, not a pointer

There is no alias table, no `latest` tag and nothing to keep in step. The newest version is the
first element of qits-artifacts' own version list, read on every request. Two things follow, and
both are the reason it is done this way: a release becomes the latest the instant its publish
lands, and a redeploy of this service loses nothing because there was nothing to lose.

The redirect is **302, not 301**. What `/docs/<site>/` means changes with every release, so
a permanent redirect would pin a reader's browser to whatever was newest the first time they
visited, for as long as their cache lived.

### Caching is restated here, not passed through

Every URL is version-addressed, so its bytes can never change — but `index.html` is what a `latest`
redirect lands on, and a browser holding it for a year would keep rendering an old release's entry
point after following a redirect to a new one. So the entry point revalidates
(`max-age=0, must-revalidate`) and everything the bundle references, whose names carry a content
hash, is `immutable`. Only this service knows which URL the reader is on, which is why upstream's
header is replaced rather than forwarded.

### Errors are plain text, always

The client is a browser assembling a website, so an HTML error body is exactly what it will render
in place of the page that was asked for — a failed stylesheet would come back looking like a page.
Nothing calls `rc.fail()`, because Quarkus' own failure handler answers with HTML.

**404 and 502 are kept apart on purpose.** 404 means the URL names nothing; 502 means qits-artifacts
could not be asked. Collapsing them sends whoever is debugging to the wrong service, which is the
most expensive wrong answer a component in the middle can give.

## Configuration

| Key | Default | What |
| --- | --- | --- |
| `qits.docs.artifacts-url` | `http://dev-qits-artifacts:8080/artifacts/docs/docs` | the store, **including** its repository segment |
| `qits.docs.connect-timeout` | `PT2S` | |
| `qits.docs.request-timeout` | `PT30S` | bounds the response *head*, not the transfer |

The artifacts URL is the same value qits-ci injects into a publishing step as `$QITS_DOCS_URL`, and
that is deliberate: a deployment configures one address, and the publisher and the reader cannot
disagree about where documentation lives.

The in-network alias is the right default for the reason the npm and maven roots give — this process
dials it from inside `qits-net`, so a host-published mapping (a local stack's `localhost:8081`) must
**not** be substituted here.

## Deployment

One published port, and one host of its own: `docs.<env>.<domain>`. The edge serves **the client at
`/`** there and path-routes `/docs/**` to this service from every host on the platform, verbatim —
there is no unprefixed spelling of the machine surface, on `qits-net` either. The segment is a
literal in `DocsPaths` and no config key moves it.

Readiness is the stock `/docs/q/health/ready`, and it is stock **deliberately**: this
service has no state whose health could differ from "the process is up", and a check that dialled
qits-artifacts would take this container down for an outage it is designed to survive by answering
502.

> **`q/` and `api/` are reserved out of the site grammar, and that is not tidiness.**
> `q/health/ready` is three perfectly valid site-name segments. Without the lookahead in
> `DocsPaths.NOT_RESERVED`, a readiness probe resolves as a request for a site called
> `q/health/ready`, answers 404, and the deployment's health gate never goes green against a process
> that is running perfectly. Route order would also fix it, and would keep being right exactly until
> someone reordered `DocsRoutes.init` for readability.

## The client

`src/main/webui` is the [qits-docs-frontend](https://github.com/QuicklyIterateTheSoftware/qits-docs-frontend)
submodule, built and served by Quinoa **at the root of this service's host** (`baseHref: /`,
`quarkus.quinoa.ui-root-path=/`). Two pages, and a scoped spelling of each:

    /                                    the door sign
    /read/<site>/-/<version>             one bundle, in a frame
    /<slug>/<category>/<repo>/           the same, for one repository's documentation
    /<slug>/<category>/<repo>/read/...

`/docs/**` is the machine surface beside it — the catalog, and the bundle bytes.

**The reader is one frame under the platform sidebar.** A bundle is a whole application — Storybook
ships its own full-height sidebar — so the client cannot put the version picker inside it and must
not try. Where you are and which version you are reading live in the sidebar's sub-menu; the page
itself is the `<iframe>`, edge to edge.

> **`DocsRoutes.ROUTE_ORDER` is 20 000, between Quinoa's two routes.** Quinoa registers static
> resources at 1060 and its SPA fallback near 40 000; these routes sit above the first and below the
> second. The client is at `/` and these are under `/docs`, which is an ignored prefix besides, so
> neither boundary is load-bearing any more — but the first one was: with the client under this
> segment, `SITE` claimed its own `main-<hash>.js` bundle, asked the store for the versions of a
> site by that name and 404'd, so the index rendered with every asset gone. Both numbers are read
> off the Quinoa jar and are not API; re-check them when the pin moves.

`/docs/@qits` is a **scope, not a site**, and answers a plain-text 404: there is no newest version
of a scope to redirect to. It used to fall through to a client page under this segment; the client
is at the host root now and `/docs` is an ignored prefix, so the fall-through would have reached
Quarkus' own HTML 404 instead. `read/` is no longer reserved out of the site grammar either — the
reader is at `/read/**`, outside `/docs` entirely. `q/` and `api/` still are.

## User stories

`src/test/java/.../stories/` is this repository's whole integration suite, written as
[userflows](https://github.com/QuicklyIterateTheSoftware/qits-userflows-javalib): each test is a
`@UserStory` method that asserts *and* emits its own documentation — steps, a narrative, and a
**network diagram drawn from traffic that was observed rather than narrated**. `mvn verify`
regenerates `target/userstories/`, and `.config/qits/ci-event-userflows.yml` publishes it per commit
as `@userflows/qits-docs`, which is a site this service then serves. A reader following the stories
arrives at the stories.

| Story | Category | What it pins |
| --- | --- | --- |
| The catalog groups every published site by its scope | `reading` | the flat list is grouped **here**; the unscoped group; the store asked once — and, because this class drains first, that the boot dialled nothing |
| The branch filter is the store's question, not this service's | `reading` | `?branch=` leaves as `?meta.git.branch.name=`, percent-encoded; filtered-to-nothing is 200 and not 404 |
| A version published a second ago is the one a reader lands on | `reading` | publish → read, end to end: `latest` as a query, both redirects, `ETag` passed through and `Cache-Control` replaced |
| The stories this run publishes are read back through the same door | `reading` | a userflows bundle's `files` and dotted `metadata`, verbatim; the bundle wire three segments deep |
| A store that cannot answer is never an empty shelf | `refusals` | 404 vs 502, and the three different ways a store fails: a status, a payload, and silence |
| The answers that cost the store nothing | `refusals` | the three answers decided from the URL alone — with a live recorder on the store proving it was never dialled |

Three things are worth knowing before adding one:

- **One `@TestProfile` for all of them** (`stories/support/StoryProfile`), because a profile is what
  failsafe launches a process for. Two profiles would be two qits-docs and a diagram whose traffic
  landed in whichever one was running.
- **The far side is `stories/support/StoryStore`**, a real listener speaking qits-artifacts' docs
  plane — the `/-/` grammar parsed, the `?meta.…` filter honoured, per-file content types and
  ETags, and a `PUT` that really publishes. It replaced a generic recording mock, because every
  property these stories are about lives exactly where a canned-JSON stub cannot go.
- **Nothing narrates an edge.** The incoming half is the framework's shipped RestAssured tap; the
  outgoing half is the store's own access log. A story asserts and notes; it draws nothing.

## Not built yet

- **A native IT.** `mvn verify -Dnative` compiles the binary but nothing yet drives it. The claim
  worth proving that way is that `java.net.http` streaming survives the compile. (The stories above
  would run against it unchanged — the `native` profile launches the same catalogue.)
- **A percent-encoded site name.** `/docs/%40qits/ui-components` does **not** resolve:
  `DocsPaths`' character classes admit no `%`, so it falls past the site route and reaches Quarkus'
  own HTML 404 — the one body shape this service otherwise never produces. No browser or `curl`
  encodes `@` in a path, so nothing real hits it, and it was found only because RestAssured does.
  Worth a decision (decode before matching, or catch the fall-through) rather than a surprise.
- **The span export.** `quarkus-opentelemetry` is the only dial-out besides the store and it is
  disabled in the story profile, exactly as `%dev` and `%test` disable it. An exporter flushes on
  its own schedule and would draw an arrow into whichever diagram happened to be open, so no story
  covers it and no story claims its absence either.
- **Anything but Storybook.** Every bundle so far is one, and the reader assumes only that a bundle
  has an `index.html` and refers to its assets relatively. A generator that does neither would need
  the reader to learn something.
