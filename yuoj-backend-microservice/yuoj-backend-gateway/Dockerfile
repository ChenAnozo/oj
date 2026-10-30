ARG JAVA_BASE_IMAGE=eclipse-temurin:8-jre-jammy@sha256:49b31e882495abeb1118de417ee696bdc929d2de238730920f1468025fc393a4
FROM ${JAVA_BASE_IMAGE}

WORKDIR /app
ENV TZ=Asia/Shanghai

COPY --chown=10001:10001 target/yuoj-backend-gateway-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001

EXPOSE 8101

# Select the Spring profile at runtime, e.g. SPRING_PROFILES_ACTIVE=prod.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
