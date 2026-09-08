# syntax=docker/dockerfile:1

# ---- Dependency stage ---------------------------------------------------
# Only the POMs are copied here, so this layer - and the dependency download it
# performs - is reused on every build where no POM changed. Previously the whole
# source tree was copied before Maven ran, which meant editing a single Java file
# invalidated the layer and Maven re-resolved and re-downloaded the entire
# dependency tree (Spring Boot, Spring AI, Stripe, Twilio, TwelveMonkeys) on
# every deploy. That was the bulk of the build time.
#
# NOTE: adding a module to the reactor means adding its POM here too. If you
# forget, nothing breaks - the build stage below still compiles it - you only
# lose the caching for that module's dependencies.
FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build

COPY pom.xml .
COPY commons/pom.xml commons/
COPY auth-user-module/pom.xml auth-user-module/
COPY booking-module/pom.xml booking-module/
COPY tracking-module/pom.xml tracking-module/
COPY driver-fleet-module/pom.xml driver-fleet-module/
COPY driver-module/pom.xml driver-module/
COPY payment-module/pom.xml payment-module/
COPY notification-module/pom.xml notification-module/
COPY complaint-module/pom.xml complaint-module/
COPY chat-module/pom.xml chat-module/
COPY feedback-rating-module/pom.xml feedback-rating-module/
COPY admin-module/pom.xml admin-module/
COPY sos-module/pom.xml sos-module/
COPY ai-agent-module/pom.xml ai-agent-module/
COPY app/pom.xml app/

# Best-effort: go-offline cannot resolve the sibling com.trackngo modules, which
# do not exist until the build stage runs, so it always reports a failure at the
# end. Everything it did manage to download is still in the cache by then, which
# is the entire point, so the failure is ignored deliberately.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q dependency:go-offline -DskipTests || true

# ---- Build stage --------------------------------------------------------
# Builds the whole multi-module Maven reactor (commons, auth-user-module,
# booking-module, ... , app) so the app module's inter-module dependencies
# resolve, then packages the Spring Boot jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY --from=deps /root/.m2 /root/.m2
COPY . .

# No `clean`: the build context is fresh on every image build, so there is
# nothing to clean and it only costs time. -T 1C builds independent modules
# (booking, chat, sos, payment) in parallel across the available cores.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B package -DskipTests -T 1C

# ---- Extract stage ------------------------------------------------------
# Splits the Spring Boot jar into its layers so Docker can cache them
# separately. Dependencies change rarely and application classes change every
# deploy; as one fat jar they shared a single ~60MB layer that was pushed and
# pulled in full every time.
FROM eclipse-temurin:21-jre AS extract
WORKDIR /extract
COPY --from=build /build/app/target/app-1.0.0-SNAPSHOT.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ---- Runtime stage --------------------------------------------------------
# Non-Alpine JRE: the app renders profile-picture thumbnails via AWT/ImageIO
# (Thumbnails/Scalr), which wants a full glibc + fontconfig environment that
# Alpine's musl base doesn't reliably provide.
FROM eclipse-temurin:21-jre
WORKDIR /app

# Ordered least-changing first, so a code-only deploy rebuilds and ships only
# the last, smallest layer.
COPY --from=extract /extract/dependencies/ ./
COPY --from=extract /extract/spring-boot-loader/ ./
COPY --from=extract /extract/snapshot-dependencies/ ./
COPY --from=extract /extract/application/ ./

# JVM settings for a small container - AWS Free Tier t3.micro is 1 vCPU / 1GB,
# and Render's free instance is comparable. Set through JAVA_TOOL_OPTIONS rather
# than the entrypoint so they can be retuned from the platform's environment
# variables without rebuilding the image.
#
#   MaxRAMPercentage=65      sizes the heap from the *container* limit instead of
#                            the host's RAM, leaving room for metaspace, thread
#                            stacks and the native buffers ImageIO allocates when
#                            resizing a profile picture.
#   UseSerialGC              beats G1 on a single-core instance: G1's concurrent
#                            collector threads have no spare core to run on, and
#                            its bookkeeping costs more memory.
#   MaxMetaspaceSize         caps class metadata, which for a 15-module Spring
#                            app is substantial and otherwise grows unbounded.
#   ExitOnOutOfMemoryError   makes the platform restart the container instead of
#                            leaving it wedged in a GC death spiral, which to a
#                            user looks like an API that never responds.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"

# Documents the default local port; the platform supplies its own $PORT at
# runtime, which application.yml already reads via server.port: ${PORT:8080} —
# nothing in this image hardcodes a port.
EXPOSE 8080

# JarLauncher rather than `java -jar`: the layers above are already extracted, so
# the JVM reads plain class files instead of unpacking a nested jar on every
# start. On Spring Boot 3.2 this class lives in ...loader.launch, not ...loader.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
