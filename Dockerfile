FROM gradle:9.8.0-jdk25@sha256:30f0c2e94f2b91cffaa192cebc86f1ba8efc574b9a8e93b33164e5f2ed839c08 AS build
WORKDIR /src

# Build scripts and wrapper only, so a source-only change (below) doesn't bust the
# dependency-resolution layer and force a re-download every build.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./

# Warm the Gradle dependency cache. --mount=type=cache is a BuildKit cache mount:
# it's backed by BuildKit's own cache store, not a union-fs layer, so nothing
# written under it is committed to any image layer — confirmed by inspecting the
# built image's history/layers below (see docs/runtime-notes.md). Target is
# /home/gradle/.gradle: this image's GRADLE_USER_HOME is unset, so Gradle falls
# back to $HOME/.gradle; the image runs as root by default (HOME=/root), and
# /root/.gradle is a symlink baked into this image pointing at
# /home/gradle/.gradle — confirmed with `docker run gradle:9.6.1-jdk25 sh -c
# 'readlink /root/.gradle'`.
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew --no-daemon dependencies

# The mini app (web/). `installDist` triggers `webBuild` through `processResources`
# (see build.gradle.kts): the `com.github.node-gradle.node` plugin downloads a pinned Node
# into .gradle/nodejs/ (needed only so Bun's own scripts, which all shebang `#!/usr/bin/env
# node`, have a real Node to run under — see build.gradle.kts's comment on the `node {}`
# block for why), and `bunToolInstall` installs a pinned, npm-sha512-verified Bun
# (web/tools/) that then installs and runs web/'s own dependencies (web/bun.lock). No
# separate Node/Bun stage — the runtime image below stays the same JRE-only one.
#
# Only the lockfiles/manifests are copied first, and the toolchain is actually installed
# here (nodeSetup/bunToolInstall/bunInstall), so this RUN — not just the COPY — sits ahead
# of `COPY src`: a Kotlin-only change now reuses this whole layer instead of re-downloading
# Node, running `npm ci` and `bun install` every time (the previous comment here claimed
# that reuse without an intervening RUN, which was false — nothing was cached until a build
# step actually ran between the two COPYs).
#
# `/home/gradle/.gradle` is the same Gradle-home cache mount as above. `/root/.npm` is
# npm's own download/package cache (HOME=/root in this image, confirmed via `npm config get
# cache` after nodeSetup's downloaded npm actually populates it under a clean build
# context), used by `bunToolInstall`'s `npm ci`. `/root/.bun/install/cache` is Bun's package
# cache (confirmed populated the same way by `bunInstall` under a clean context — no
# BUN_INSTALL/BUN_INSTALL_CACHE_DIR override in play, so this is Bun's real default). All
# three are BuildKit cache mounts (see the note above on what that means for the final
# image), so none of them land in a layer — only the *outputs* below do.
#
# Deliberately NOT cache-mounted: `.gradle/nodejs` (the downloaded Node) and
# `web/node_modules` / `web/tools/node_modules` (installed deps). Those must be committed
# to this layer, not hidden behind a cache mount, or `webBuild`/`installDist` further down
# won't see them.
COPY web/package.json web/bun.lock ./web/
COPY web/tools/package.json web/tools/package-lock.json ./web/tools/
RUN --mount=type=cache,target=/home/gradle/.gradle \
    --mount=type=cache,target=/root/.npm \
    --mount=type=cache,target=/root/.bun/install/cache \
    ./gradlew --no-daemon nodeSetup bunToolInstall bunInstall

# .dockerignore excludes web/node_modules and web/tools/node_modules from the build
# context, so this COPY (Docker merges a directory COPY into an existing destination
# rather than replacing it) cannot clobber the node_modules the RUN above just installed —
# it only ever (re-)writes the same web/ source files.
COPY web ./web

COPY src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew --no-daemon installDist

# The distroless runtime stage below has no shell, so it can't mkdir/chown its own
# data directory. Seed one here, owned by the hardened image's actual runtime user
# (bellsoft/hardened-liberica-runtime-container ships "appuser", uid:gid 10001:10001
# — confirmed by `docker cp`-ing /etc/passwd out of the distroless image, since it
# has no cat/shell to read it in place), and copy it in below.
RUN mkdir -p /data-seed && chown 10001:10001 /data-seed

# jre-25-cds-distroless-glibc: JRE (not JDK — this is a runtime-only image), Java
# 25 to match the toolchain above, glibc (not musl/Alpine) since that's the
# variant this project has actually validated Tink/H2/Exposed against, and the
# "cds" build so the JDK's own class-data-sharing archive is present and used
# automatically (no extra flags needed — CDS auto-enables from the default
# archive on JDK 19+; this image just ships one prebuilt for its own runtime
# classes rather than the JVM building one from scratch on first launch), for
# faster cold-start — the bot restarts on every deploy and after upgrades, so
# JVM startup time is not a one-off cost.
FROM bellsoft/hardened-liberica-runtime-container:jre-25.0.4_9-cds-distroless-glibc@sha256:7570c559c456ed2d4761298a294b502e99e1183119179844edee1045a9869b39
WORKDIR /app

# lib/ is the app's code: root-owned, read-only to the running user (least
# privilege — the process never needs to modify its own jars).
COPY --from=build /src/build/install/exchange-bot/lib ./lib
# data/ is the app's mutable state: owned by appuser so it can write the H2
# database there, and matching the named volume compose.yaml mounts at this
# path (Docker seeds a named volume's ownership from the image on first use).
COPY --from=build --chown=10001:10001 /data-seed ./data

# Numeric, not "appuser": a named USER only resolves if whatever reads the image
# config can look the name up in the image's /etc/passwd, and that assumption
# breaks for Kubernetes runAsNonRoot (which rejects a non-numeric USER outright,
# since it can't otherwise prove the image isn't root) and is fragile generally.
# 10001:10001 is this image's actual "appuser" uid:gid — confirmed by `docker cp`
# -ing /etc/passwd out of the distroless image (it has no shell/id/cat to read it
# in place) and separately by `docker top` on a running container (see
# docs/runtime-notes.md). The base image already defaults to this same identity
# (its own USER appuser), so this line is redundant with the image default in
# practice — kept explicit anyway so the running uid is asserted here rather than
# inherited silently from whatever the base image happens to ship.
USER 10001:10001
VOLUME ["/app/data"]
ENV DB_PATH=/app/data/exchange
ENV TZ=UTC
# Only used when MINIAPP_URL is set; the reverse proxy reaches it on the compose network.
EXPOSE 8080
# The generated installDist launcher (bin/exchange-bot) is a bash script and can't
# run here — distroless has no shell — so java is invoked directly instead.
# applicationDefaultJvmArgs in build.gradle.kts (-Duser.timezone=UTC) is baked into
# that bypassed launcher, so it's carried explicitly below; if this flag is ever
# "tidied up" by someone who doesn't know that, the timezone pin silently
# disappears and timestamps drift on any DST-observing host. ENV TZ=UTC above is
# belt-and-braces alongside it, not a replacement for it. "/app/lib/*" is expanded
# by the JVM's own classpath-wildcard handling (a `java` launcher feature, not
# shell globbing), so this works fine in exec form with no shell present.
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-cp", "/app/lib/*", "fxbot.MainKt"]
