# Repository guidance

- Use Java 25, Maven, and Spring Boot 4.1.1. Do not apply Spring Boot 3 examples or upgrade the Boot version without an explicit request.
- Use Flyway SQL migrations for schema changes. Keep `spring.jpa.hibernate.ddl-auto=validate`; Hibernate must not create or update schema.
- Use JPA for writes and `JdbcClient` for aggregate/report queries. Store prices, volume, and money as `NUMERIC` / `BigDecimal`.
- Keep JVM and Hibernate timestamps in UTC. Preserve broker-local timestamps when converting them depends on an unresolved offset or DST rule.
- Organize backend code by feature under `com.trading.journal`. Keep API routes under `/api/v1/` and return `ProblemDetail` for API errors.
- Treat every user-owned row as tenant data. Verify account ownership for each request and scope queries by user.
- Keep importer behavior aligned with the design: deduplicate within an account, preserve partial closes, match sources by ticket, and prefer Trades Report values for close time and P&L.
- Never commit broker exports containing account logins. Add sanitized fixtures for import tests.
