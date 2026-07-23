FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -DskipTests dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 agent
COPY --from=build /workspace/target/koawa-agent-*.jar app.jar
USER agent
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
