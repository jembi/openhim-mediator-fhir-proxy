FROM maven:3-jdk-8-onbuild
CMD ["java", "-jar", "target/mediator-fhir-proxy-1.0.2-jar-with-dependencies.jar"]
