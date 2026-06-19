# syntax=docker/dockerfile:1

# =========================================================
# 1) 빌드 스테이지 — Gradle Wrapper + JDK 21로 bootJar 생성
#    멀티아치 베이스 이미지라 arm64 호스트(t4g)에서 빌드 시
#    자동으로 arm64 산출물이 만들어진다.
# =========================================================
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 의존성 레이어 캐싱: 빌드 스크립트/래퍼만 먼저 복사
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 jar 빌드 (테스트는 CI에서 수행, 이미지 빌드 시 제외)
COPY src src
RUN ./gradlew --no-daemon clean bootJar -x test

# =========================================================
# 2) 런타임 스테이지 — JRE 21, 비루트 사용자로 실행
# =========================================================
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# 비루트 실행 사용자
RUN groupadd --system spring && useradd --system --gid spring spring

COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown spring:spring app.jar
USER spring

EXPOSE 8080

# JAVA_OPTS로 힙 상한 등 주입 (compose에서 -Xmx 등 전달)
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
