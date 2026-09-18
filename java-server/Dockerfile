FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin mir2 \
    && mkdir -p /app/data \
    && chown -R mir2:root /app
COPY --from=build --chown=mir2:root /workspace/bootstrap/target/mir2-server.jar /app/mir2-server.jar
USER 10001
EXPOSE 7000 7100 7200
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/mir2-server.jar"]
