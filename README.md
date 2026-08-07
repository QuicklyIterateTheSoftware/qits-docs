# qits-platform-docs

**The platform's reading room.** One address where every documentation site the platform has
published can be read — `/platform-docs/@qits/ui-components/` and you are looking at the newest
release's workbench.

A small, stateless Quarkus 3 (Java 25) application that compiles to a **GraalVM native binary**. It
has no database, no ORM, no cache and no state of its own: it resolves a readable URL to a
version-addressed one and streams the bytes qits-artifacts holds.

    ./mvnw verify                                  # no docker, no network, no qits-artifacts
    ./mvnw package && java -jar target/quarkus-app/quarkus-run.jar
    ./mvnw verify -Dnative && ./target/qits-platform-docs

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
| `GET /platform-docs/<site>` | 302 → the newest version's directory |
| `GET /platform-docs/<site>/` | the same |
| `GET /platform-docs/<site>/-/<version>` | 302 → the same path **with a trailing slash** |
| `GET /platform-docs/<site>/-/<version>/` | the bundle's `index.html` |
| `GET /platform-docs/<site>/-/<version>/<path>` | one file, streamed |
| `GET /platform-docs/api/sites` | the catalog, grouped by scope |
| `GET /platform-docs/api/versions?site=<name>` | one site's versions, newest first |

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

The redirect is **302, not 301**. What `/platform-docs/<site>/` means changes with every release, so
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
| `qits.platform-docs.artifacts-url` | `http://qits-artifacts:8080/artifacts/docs/docs` | the store, **including** its repository segment |
| `qits.platform-docs.connect-timeout` | `PT2S` | |
| `qits.platform-docs.request-timeout` | `PT30S` | bounds the response *head*, not the transfer |

The artifacts URL is the same value qits-ci injects into a publishing step as `$QITS_DOCS_URL`, and
that is deliberate: a deployment configures one address, and the publisher and the reader cannot
disagree about where documentation lives.

The in-network alias is the right default for the reason the npm and maven roots give — this process
dials it from inside `qits-net`, so a host-published mapping (a local stack's `localhost:8081`) must
**not** be substituted here.

## Deployment

One published port, fronted by qits-gateway, which routes `/platform-docs/*` here verbatim and
rewrites nothing — there is no unprefixed spelling, on `qits-net` either. The segment comes from
`QitsService.PLATFORM_DOCS`, derived from this repository's name; it is a literal in `DocsPaths` and
no config key moves it.

Readiness is the stock `/platform-docs/q/health/ready`, and it is stock **deliberately**: this
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

`src/main/webui` is the [qits-platform-spa-docs](https://github.com/QuicklyIterateTheSoftware/qits-platform-spa-docs)
submodule, built and served by Quinoa. Three layers over the same store:

    /platform-docs/                          what publishes documentation, by scope
    /platform-docs/@qits                     what that scope publishes
    /platform-docs/read/<site>/-/<version>   one bundle, with a version picker beside it

**The reader shows two navigations side by side, and that is the arrangement.** A bundle is a whole
application — Storybook ships its own full-height sidebar — so the client cannot put the version
picker inside it and must not try. It owns a narrow rail (where you are, which version) and hands
the rest to an `<iframe>`.

> **`DocsRoutes.ROUTE_ORDER` is 20 000, between Quinoa's two routes, and the client does not render
> without it.** Quinoa registers static resources at 1060 and its SPA fallback near 40 000. Below
> 1060, `SITE` claims the client's own `main-<hash>.js` — one alphanumeric segment, a perfectly good
> site name — asks the store for its versions and answers 404: the index renders and every asset is
> gone. At or past 40 000 the fallback answers bundle paths with `index.html` instead. Both numbers
> are read off the Quinoa jar and are not API; re-check them when the pin moves.

`/platform-docs/@qits` is the one path this service **declines**: a single segment beginning with
`@` is a scope, and `latest()` calls `next()` so Quinoa's fallback serves the client. `read/` is
reserved out of the site grammar for the same reason, beside `q/` and `api/`.

## Not built yet

- **A native IT.** `mvn verify -Dnative` compiles the binary but nothing yet drives it. The claim
  worth proving that way is that `java.net.http` streaming survives the compile.
- **Anything but Storybook.** Every bundle so far is one, and the reader assumes only that a bundle
  has an `index.html` and refers to its assets relatively. A generator that does neither would need
  the reader to learn something.
