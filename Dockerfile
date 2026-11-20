# Packages the jar built by "mvn package": see README.md.
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 10001 notes
COPY src/notes-core/target/notes-core-*.jar /app/notes-core.jar

USER notes
EXPOSE 8080
VOLUME /config
ENTRYPOINT ["java", "-jar", "/app/notes-core.jar"]
CMD ["/config"]
