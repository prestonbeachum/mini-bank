# 2. Spring Boot 4 instead of Spring Boot 3

Date: 2026-11-11 · Status: Accepted

## Context
The build doc names Java 21 and Spring Boot 3. By November 2026 Spring Initializr only offers Boot 4.x, and Boot 3.5's open-source support ended in mid-2026.

## Decision
Use Spring Boot 4.1.x on Java 21. Same programming model (Spring MVC, JDBC, Flyway, Actuator); modular starters (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`).

## Consequences
Guides written for Boot 3 may show older package names or starters. Lambda Web Adapter and SnapStart compatibility still needs verifying against Boot 4 (build doc section 17).
