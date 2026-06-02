FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle

RUN chmod +x gradlew \
    && ./gradlew --no-daemon dependencies --configuration compileClasspath >/dev/null \
    && ./gradlew --no-daemon dependencies --configuration runtimeClasspath >/dev/null

COPY src ./src

RUN ./gradlew --no-daemon clean bootJar -x test \
    && cp "$(find build/libs -name '*.jar' ! -name '*-plain.jar' | head -n 1)" /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy

RUN useradd --system --uid 10001 --create-home app

WORKDIR /app

COPY --from=build /workspace/app.jar /app/app.jar

USER app

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
