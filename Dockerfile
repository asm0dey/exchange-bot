FROM gradle:9.6.1-jdk25 AS build
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
FROM bellsoft/hardened-liberica-runtime-container:jre-25-cds-distroless-glibc
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
