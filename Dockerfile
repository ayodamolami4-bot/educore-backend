FROM maven:3.9.11-eclipse-temurin-17 AS build
RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY . .
ENV MAVEN_OPTS="-Xmx512m"
RUN chmod +x mvnw && ./mvnw -B package -DskipTests
RUN javac -d /tmp infra/Healthcheck.java

FROM eclipse-temurin:17-jre
WORKDIR /app
ARG SERVICE
COPY --from=build --chown=10001:10001 /workspace/${SERVICE}/target/${SERVICE}-0.1.0-SNAPSHOT.jar /app/app.jar
COPY --from=build --chown=10001:10001 /tmp/Healthcheck.class /app/Healthcheck.class
COPY --from=build --chown=10001:10001 /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf /app/DejaVuSans.ttf
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-Xms32m -Xmx192m -Duser.timezone=Africa/Lagos"
ENV SERVER_PORT=8080
ENV EDUCORE_PDF_FONT=/app/DejaVuSans.ttf
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=4s --start-period=35s --retries=20 CMD ["java", "-cp", "/app", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
