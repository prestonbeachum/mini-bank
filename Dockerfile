# One fat JAR, built once. Later phases wrap the same JAR as a Lambda zip.
FROM eclipse-temurin:24-jdk AS build
WORKDIR /src/app
COPY app/.mvn .mvn
COPY app/mvnw app/pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY db /src/db
COPY app/src src
RUN ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:24-jre
WORKDIR /app
COPY --from=build /src/app/target/mini-bank-*.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
