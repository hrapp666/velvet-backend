# ============================================================================
# Velvet Backend · 多阶段构建
# ============================================================================

# ─── Stage 1: Build ───
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# ─── Stage 2: Runtime ───
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 创建非 root 用户
RUN addgroup -S velvet && adduser -S velvet -G velvet

COPY --from=build /app/target/*.jar app.jar
RUN chown -R velvet:velvet /app
USER velvet

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/v1/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
