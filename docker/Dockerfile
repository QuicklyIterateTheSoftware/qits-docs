# qits-platform-docs as a GraalVM-native container IMAGE — the platform's two-stage shape: a Mandrel
# builder stage compiles the binary, a minimal runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative` produces
# target/qits-platform-docs locally with no container involved. What this adds is the image around
# it — and, incidentally, a way for a machine with no GraalVM to still get a binary, since the
# builder stage brings its own native-image.
#
# Build from the repo root:
#
#     docker build -t qits/platform-docs:latest -f docker/Dockerfile .
#
# NOTE WHAT IS ABSENT, compared with every sibling that serves a client: there are no Quinoa flags,
# no node install and no pre-built bundle to carry in. This service has no webui yet — the index UI
# is `qits-platform-spa-docs` and is not wired in — so the build context is source and nothing else.
# When that lands, this stage grows the same three overrides qits-gateway's carries, and for the
# same reason (the @qits scope is reachable from no address a docker build has).

# --- stage `build`: native-compile ---------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `localhost:8081/quay/…` is qits-artifacts' pull-through mirror of quay.io: the first build fetches
# from upstream and caches it, every later build is served from local disk. The tag is upstream's,
# unchanged — the mirror revalidates it, so `jdk-25` still moves when quay moves it.
FROM localhost:8081/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole tree, not a file list — qits-gateway's reasoning verbatim: a file list has to be edited
# every time the repo gains top-level content the build reads, nothing enforces that, and the
# failure lands far from the cause. `.dockerignore` is what keeps this cheap.
COPY . .

# -Dnative flips quarkus.native.enabled (this project carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
#
# NOTE THE ABSENT -DskipTests, which every sibling's builder stage carries. This is where the suite
# runs, and it is the only place it can: the CI step container has node and docker and no JDK, so a
# `./mvnw` line there fails on JAVA_HOME. The suite needs no docker, no network and no
# qits-artifacts — it is five cases over the route grammar — so it costs seconds and it stops a
# broken grammar here rather than at a deploy.
RUN ./mvnw -B -ntp package -Dnative -Dquarkus.native.native-image-xmx=4g

# --- runtime -------------------------------------------------------------------------------------
# `localhost:8081/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM localhost:8081/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# `quarkus.package.output-name` names the binary `qits-platform-docs` (no -runner suffix), so the
# process is identifiable in `ps`/`docker top`.
COPY --from=build --chown=1001:root /src/target/qits-platform-docs /work/qits-platform-docs
RUN chmod +x /work/qits-platform-docs

EXPOSE 8080
USER 1001

# The one address this service needs is configuration: QITS_PLATFORM_DOCS_ARTIFACTS_URL. Its default
# is the qits-net alias and is right for an ordinary deployment — see README.md.
ENTRYPOINT ["/work/qits-platform-docs", "-Dquarkus.http.host=0.0.0.0"]
