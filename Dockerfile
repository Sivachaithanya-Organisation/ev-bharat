# ---- Stage 1: build the WAR (JDK 21 + Maven) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies in their own layer so code changes don't re-download them
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package

# ---- Stage 2: run on Tomcat 9 (javax.servlet) ----
FROM tomcat:9.0-jdk21-temurin

# Remove the default sample apps and deploy as ROOT so the app is served at /
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=build /app/target/ev-bharat.war /usr/local/tomcat/webapps/ROOT.war

# Run as a non-root user
RUN useradd -r -u 1001 tomcat && chown -R tomcat:tomcat /usr/local/tomcat
USER tomcat

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s \
  CMD curl -fs http://localhost:8080/api/health || exit 1

CMD ["catalina.sh", "run"]
