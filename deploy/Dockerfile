FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY backend/target/change-monitor-0.1.0.jar app.jar
ENV JAVA_OPTS="-Xms256m -Xmx512m"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
