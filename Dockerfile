# syntax=docker/dockerfile:1
#
# EarlyBird's production image (spec section 10). Built in CI only: the laptop doesn't run Docker.
#
# Stage 1 builds the jar with the Maven wrapper. It runs on the build machine's own architecture
# ($BUILDPLATFORM): a jar is the same everywhere, so an arm64 image doesn't need a slow emulated Maven build.
# Stage 2 is the small runtime image, one per target architecture (amd64 for Render, arm64 for Oracle later).

FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Dependencies in their own layer, so source-only changes don't download them again.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q dependency:resolve dependency:resolve-plugins
COPY src/ src/
# Tests ran in CI before this image is built.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q -DskipTests package \
    && java -Djarmode=tools -jar target/earlybird.jar extract --layers --destination target/extracted

FROM eclipse-temurin:25-jre
RUN groupadd --system earlybird && useradd --system --gid earlybird --home-dir /app --no-create-home earlybird
WORKDIR /app
# Boot's layers, least to most often changed, so a code change ships only the last small layer.
COPY --from=build /src/target/extracted/dependencies/ ./
COPY --from=build /src/target/extracted/spring-boot-loader/ ./
COPY --from=build /src/target/extracted/snapshot-dependencies/ ./
COPY --from=build /src/target/extracted/application/ ./
USER earlybird

# Sized for Render's free instance: 512 MB and a tenth of a CPU (spec section 4). TieredStopAtLevel=1 keeps
# only the quick JIT compiler: less compiling on a tenth of a CPU.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1"
EXPOSE 8080
# Render sets PORT; application.yml binds server.port to it. Native access is for Kafka's zstd library.
# Boot's extract keeps the uber jar's name, so the application layer holds earlybird.jar (pom.xml's finalName).
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "earlybird.jar"]
