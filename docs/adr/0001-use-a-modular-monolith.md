# Use a modular monolith with a server-rendered administration UI

The first release will be one Spring Boot application organized into Spring Modulith modules, with Thymeleaf and HTMX serving the administration UI. This keeps deployment and migration to one repository and one JAR while retaining enforceable module boundaries; a separate SPA or microservices would add build, deployment, and distributed-systems work before the ledger rules are proven.

