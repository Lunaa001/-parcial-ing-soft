# Etapa de compilacion: JDK completo y Gradle wrapper, genera el jar ejecutable
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# El repo puede tener finales de linea de Windows; el script del wrapper tiene que ser LF en Linux
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
# El jar de la app y el agente de Application Insights (version fijada en build.gradle)
RUN ./gradlew --no-daemon bootJar copyAppInsightsAgent -x test

# Etapa final: runtime Distroless (sin shell, sin gestor de paquetes), usuario no root
FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app
COPY --from=builder /workspace/build/libs/ia-perfume-advisor.jar /app/app.jar
COPY --from=builder /workspace/build/agent/applicationinsights-agent.jar /app/applicationinsights-agent.jar
ENV SPRING_PROFILES_ACTIVE=cloud \
    UPLOAD_DIR=/tmp/uploads
EXPOSE 8080
# Un solo proceso: la JVM (con el agente de Application Insights cargado adentro) ocupa el 75% de
# la memoria del contenedor. Sin APPLICATIONINSIGHTS_CONNECTION_STRING el agente no envia nada.
ENTRYPOINT ["java", "-javaagent:/app/applicationinsights-agent.jar", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
