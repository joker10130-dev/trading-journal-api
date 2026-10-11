# Trading Journal API

Spring Boot 4.1.1 backend for the trading-journal PoC. Requires Java 25 and Docker.

## Local PostgreSQL

Start the PostgreSQL 18 database:

```powershell
docker compose up -d postgres
```

The local database listens only on `127.0.0.1:5432`. Its development-only default
credentials are `trading_journal` / `local-development-only`. Override them with
`POSTGRES_USER`, `POSTGRES_PASSWORD`, and `POSTGRES_DB` before starting Compose.

Start the API with Docker Compose integration enabled:

```powershell
.\mvnw.cmd -Plocal spring-boot:run -Dspring-boot.run.profiles=local
```

Flyway owns the schema. Hibernate validates it at startup and must not create or
update tables. Springdoc is disabled by default; enable the `dev` profile to
expose `/v3/api-docs` and Swagger UI.

Run backend tests with:

```powershell
.\mvnw.cmd verify
```
