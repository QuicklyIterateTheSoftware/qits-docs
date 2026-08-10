# qits-docs as a GraalVM-native container IMAGE — the platform's two-stage shape: a Mandrel
# builder stage compiles the binary, a minimal runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative` produces
# target/qits-docs locally with no container involved. What this adds is the image around
# it — and, incidentally, a way for a machine with no GraalVM to still get a binary, since the
# builder stage brings its own native-image.
#
# Build from the repo root:
#
#     docker build -t qits/qits-docs:latest -f docker/Dockerfile .
#
# THE CLIENT IS BUILT BEFORE THIS IMAGE, NOT IN IT — qits-gateway's deviation from the platform's
# Quinoa recipe, for its reason exactly. src/main/webui is qits-spa-docs and it depends on
# @qits/ui-components, which exists ONLY on the platform's own npm registry. A RUN step in a docker
# build reaches the public internet and can reach that registry by NO address: not the qits-net
# alias, not localhost:8081, not host.docker.internal. So the install happens where it can — the CI
# step container on qits-net, or a developer's host — and this stage packages an already-built
# dist/ with Quinoa's install and build commands replaced by `--version`. An install that "worked"
# here would have resolved the @qits scope against npmjs.org, where those names do not exist.

# --- stage `build`: native-compile ---------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `localhost:8082/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the first build
# fetches from upstream and caches it, every later build is served from local disk. The tag is
# upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay moves it.
FROM localhost:8082/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
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
RUN d=src/main/webui/dist/qits-spa-docs/browser; \
    test -f "$d/index.html" || { \
      echo "No built client at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in src/main/webui first — this stage cannot, see the header." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp package -Dnative -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version

# --- runtime -------------------------------------------------------------------------------------
# `localhost:8082/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM localhost:8082/redhat/ubi9/ubi-minimal:9.6
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
