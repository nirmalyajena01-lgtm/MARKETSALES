FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/supermarket-automation-1.0.0-shaded.jar app.jar
EXPOSE 8080
CMD ["java", "-Dsupermarket.seed.defaults=true", "-jar", "app.jar", "--web", "8080"]
