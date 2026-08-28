# syntax=docker/dockerfile:1.7

FROM gradle:9.7.1-jdk21 AS builder

USER root
WORKDIR /workspace/Tovarika

COPY Tovarika/settings.gradle Tovarika/build.gradle ./

COPY tovarika-api-contract/dist /workspace/tovarika-api-contract/dist
COPY Tovarika/src ./src

RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle bootJar \
    --no-daemon \
    -PapiContractDir=/workspace/tovarika-api-contract

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup --system tovarika \
    && adduser --system --ingroup tovarika --home /app tovarika

WORKDIR /app

COPY --from=builder --chown=tovarika:tovarika \
    /workspace/Tovarika/build/libs/tovarika-0.0.1-SNAPSHOT.jar \
    /app/application.jar

USER tovarika

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
