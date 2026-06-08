# =============================================================================
# Multi-stage build for the three Spring Boot apps, parameterised by APP.
# The build stage compiles the whole Maven reactor once; Docker layer caching
# reuses it across the three images. The runtime stage copies only the selected
# app's runnable (exec-classified) fat jar.
#
# Build context = the migration/ directory. Example:
#   docker build -f infra/app.Dockerfile --build-arg APP=mocked-apps -t bank/mocked-apps .
# In practice docker-compose builds all three (see ../../docker-compose.yml).
# =============================================================================

# ---- build stage: compile the reactor ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY libs/ libs/
COPY apps/ apps/
COPY e2e-tests/ e2e-tests/
RUN ./mvnw -B -ntp -DskipTests package

# ---- runtime stage: just the selected app's runnable jar on a slim JRE ----
FROM eclipse-temurin:25-jre AS runtime
ARG APP
WORKDIR /app
# The spring-boot-maven-plugin produces the runnable jar with the 'exec' classifier.
COPY --from=build /src/apps/${APP}/target/${APP}-*-exec.jar /app/app.jar
ENV JAVA_OPTS=""
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
