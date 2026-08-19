FROM gradle:8.6-jdk17 AS builder

WORKDIR /app
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src

ARG BUILD_REV=local
RUN gradle clean build -x test --no-daemon

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=builder /app/build/libs/CloudQueryX-1.0.0.jar app.jar

ENV JAVA_OPTS="-Xms128m -Xmx768m -XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:MaxGCPauseMillis=200"
ENV CLOUDQUERYX_PORT=10000
ENV CLOUDQUERYX_BUILD_REV=${BUILD_REV}

EXPOSE 10000 8080 9090 9091

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -q --spider http://localhost:${CLOUDQUERYX_PORT}/ || exit 1

CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar web ${PORT:-${CLOUDQUERYX_PORT:-10000}}"]
