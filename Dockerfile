FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY mock-upstream/pom.xml mock-upstream/pom.xml
COPY sluice-gateway/pom.xml sluice-gateway/pom.xml
RUN mvn -B -q -DskipTests dependency:go-offline || true
COPY mock-upstream mock-upstream
COPY sluice-gateway sluice-gateway
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS mock-upstream
RUN useradd --system --uid 10001 mock
WORKDIR /app
COPY --from=build /build/mock-upstream/target/mock-upstream-1.0.0.jar /app/mock-upstream.jar
USER mock
ENV PORT=9000
EXPOSE 9000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/mock-upstream.jar"]

FROM eclipse-temurin:21-jre AS gateway
RUN useradd --system --uid 10001 sluice
WORKDIR /app
COPY --from=build /build/sluice-gateway/target/sluice-gateway-1.0.0.jar /app/sluice.jar
USER sluice
EXPOSE 8080 9090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/sluice.jar"]
