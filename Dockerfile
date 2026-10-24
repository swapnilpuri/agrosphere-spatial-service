FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN mvn --batch-mode --no-transfer-progress verify
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/agrosphere-spatial-service-0.2.1.jar app.jar
USER 10001:10001
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
