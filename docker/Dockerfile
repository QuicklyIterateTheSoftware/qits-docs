# qits-docs as a GraalVM-native container IMAGE — the platform's two-stage shape: a Mandrel
# builder stage compiles the binary, a minimal runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative` produces
# target/qits-docs locally with no container involved. What this adds is the image around
# it — and, incidentally, a way for a machine with no GraalVM to still get a binary, since the
# builder stage brings its own native-image.
#
# Build from the repo root, on the network where the platform's Maven repository answers:
#
#     docker build --network host \
#       --build-arg QITS_MAVEN_REPOSITORY_URL="http://$QITS_REGISTRY/artifacts/maven/maven" \
#       -t qits/qits-docs:latest -f docker/Dockerfile .
#
# BOTH FLAGS ARRIVED WITH THE USERFLOWS ROLLOUT, and neither has a workable default. This pom used
# to resolve everything from Maven Central — the header of .config/qits/ci-post-receive.yml said so
# at length — and it no longer does: `qits-userflows`, the TEST-scope jar the userflow story
# catalogue is written against, is published to the platform's own Maven repository and available
# from nowhere else. Test scope is not a reprieve, because this stage
# deliberately runs the suite (see the absent -DskipTests below) and Maven resolves the test
# classpath to compile it either way.
#
#   --network host        NOT a custom network: buildkit — the builder format the platform targets,
#                         because a swarm cannot assume the legacy builder — refuses custom networks
#                         on a build. Host networking is buildkit-supported and reaches the
#                         registry's host-published address.
#   --build-arg           names that address, derived from the same injected env the image tag uses,
#                         so no address is stated in the pipeline. The ARG below turns it into
#                         -Dqits.maven.repository.url, which the pom's `qits-maven` repository reads
#                         and .qits-maven-settings.xml mirrors.
#
# THE CLIENT IS BUILT BEFORE THIS IMAGE, NOT IN IT — qits-gateway's deviation from the platform's
# Quinoa recipe, for its reason exactly. src/main/webui is qits-spa-docs and it depends on
# @qits/ui-components, which exists ONLY on the platform's own npm registry. A RUN step in a docker
# build reaches the public internet and can reach that registry by NO address: not the qits-net
# alias, not registry.dev.localhost:8080, not host.docker.internal. So the install happens where it
# can — the CI step container on qits-net, or a developer's host — and this stage packages an
# already-built dist/ with Quinoa's install and build commands replaced by `--version`. An install
# that "worked" here would have resolved the @qits scope against npmjs.org, where those names do not
# exist.

# --- stage `build`: native-compile ---------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.dev.localhost:8080/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the
# first build fetches from upstream and caches it, every later build is served from local disk. The
# tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay moves
# it.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
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
# `./mvnw` line there fails on JAVA_HOME. The suite needs no docker and no running qits-artifacts —
# it is a handful of cases over the route grammar and the version parse — so it costs seconds and it
# stops a broken grammar here rather than at a deploy. What it DOES need since the userflows
# rollout is the platform's Maven repository, to resolve the test-scope jar; that is what the
# two flags in the header are for.
# The `cp` onto itself before the build is not redundant: Quinoa MOVES build-dir into
# target/quinoa/build, and overlayfs cannot rename a directory that still lives in a LOWER image
# layer. It answers EXDEV, the JDK falls back to its cross-file-store path, and that path refuses a
# non-empty directory — the build dies seconds in with DirectoryNotEmptyException naming the source
# and explaining nothing. Every sibling whose builder stage CREATES dist is immune by accident;
# ours arrives by COPY. It must be in THIS RUN, because a layer boundary is what it is undoing.
#
# The `test -f` stops a context with no bundle before the multi-minute native compile. A forgotten
# `npm run build` in the CI step is then a red build rather than a service that ships without its
# reading room.
#
# `-s .qits-maven-settings.xml` is what lets the `qits-maven` repository be an http:// URL at all:
# Maven blocks external http repositories by default, and an exact repository-id mirror is the one
# thing that outranks the blocker. The settings file permits that one id and nothing else.
#
# THE ABSENT -DskipITs IS NOT AN OVERSIGHT EITHER. pom.xml now sets `skipITs` false — this
# repository's integration tests are all userflow stories, and they are cheap — but failsafe binds
# to the integration-test phase, which is AFTER package. Stopping at `package` is what keeps this stage
# from launching the artifact it just compiled.
ARG QITS_MAVEN_REPOSITORY_URL=http://registry.dev.localhost:8080/artifacts/maven/maven
ENV QITS_MAVEN_REPOSITORY_URL=$QITS_MAVEN_REPOSITORY_URL

# --- and where Maven Central is mirrored, when it is ----------------------------------------------
# The pipeline passes the platform's pull-through (qits-platform-mirror's central proxy, the same
# authority the FROM lines above pull through); EMPTY — the default — means direct Maven Central.
# `.qits-maven-settings.xml` activates its central-proxy profile only on a non-empty value, so a
# bootstrap or hand build that passes nothing resolves exactly as it always did.
ARG QITS_MAVEN_CENTRAL_URL=
ENV QITS_MAVEN_CENTRAL_URL=$QITS_MAVEN_CENTRAL_URL

# The two secret mounts put the CI run's commissioned client into THIS `RUN`'s environment and
# nowhere else — not a layer, not the image history. That is why they are not build args: this image
# is pullable, and a build arg is recorded where anyone who pulls it can read it.
# `.qits-maven-settings.xml` reads the two variables as the `qits-maven-network` server.
# The `RUN` reads the two mounted files itself: docker 26's BuildKit has no `env=` mount option.
#
# Absent secrets leave both variables empty, which is the state every build is in today and the
# state a host-side `docker build` is in permanently: Maven sends credentials only when a repository
# answers 401, and the registry does not yet.
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    export QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)"; \
    export QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)"; \
    d=src/main/webui/dist/qits-spa-docs/browser; \
    test -f "$d/index.html" || { \
      echo "No built client at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in src/main/webui first — this stage cannot, see the header." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -s .qits-maven-settings.xml package -Dnative \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false
# cyclonedx.skipNotDeployed=false is LOAD-BEARING, measured on qits-artifacts' first SBOM release
# (2026.902.45658). `<packaging>quarkus</packaging>` skips maven-deploy — this project ships as this
# image, not as a jar — and the plugin SKIPS deploy-skipping modules by default, with a green build
# and one INFO line. The sbom stage's COPY then fails on a file nothing wrote. There is no
# deploy configuration anywhere in pom.xml to make that visible, which is exactly why the flag reads
# as redundant and is not. The property spelling matters too: the bare -DskipNotDeployed=false is
# silently ignored; only the cyclonedx.-prefixed one reaches the plugin.
#
# NOTE THE TARGET PATH BELOW. This repository is ONE module — no `service/`, no `-pl` on the line
# above — so the document lands in /src/target beside the binary, where every sibling's is one level
# deeper.

# --- sbom ------------------------------------------------------------------------------------------
# The project's CycloneDX document, exported so the release step can PUT it to the artifacts store. A
# stage rather than a layer of the runtime image, and BEFORE the runtime stage on purpose: the last
# stage is what a plain `docker build` builds, and that must stay the image. The release step reaches
# this one with `--target sbom --output type=local` using the SAME args and secrets as the image
# build, so every layer above is a cache hit and the export costs seconds — which matters more here
# than anywhere, since this stage's mvnw also runs the suite. `makeBom` rides on that one invocation
# rather than a second one, for the same reason: one RUN, one layer, one truth.
FROM scratch AS sbom
COPY --from=build /src/target/sbom.json /sbom.json

# --- runtime -------------------------------------------------------------------------------------
# `mirror.dev.localhost:8080/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# `quarkus.package.output-name` names the binary `qits-docs` (no -runner suffix), so the
# process is identifiable in `ps`/`docker top`.
COPY --from=build --chown=1001:root /src/target/qits-docs /work/qits-docs
RUN chmod +x /work/qits-docs

EXPOSE 8080
USER 1001

# The one address this service needs is configuration: QITS_DOCS_ARTIFACTS_URL. Its default
# is the qits-net alias and is right for an ordinary deployment — see README.md.
ENTRYPOINT ["/work/qits-docs", "-Dquarkus.http.host=0.0.0.0"]
