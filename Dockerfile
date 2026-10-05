# Etapa de compilacion: JDK completo y Gradle wrapper, genera el jar ejecutable
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# El repo puede tener finales de linea de Windows; el script del wrapper tiene que ser LF en Linux
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# Etapa final: runtime Distroless (sin shell, sin gestor de paquetes), usuario no root
FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app
COPY --from=builder /workspace/build/libs/ia-perfume-advisor.jar /app/app.jar
ENV SPRING_PROFILES_ACTIVE=cloud \
    UPLOAD_DIR=/tmp/uploads
EXPOSE 8080
# Un solo proceso: la JVM ocupa el 75% de la memoria del contenedor
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
