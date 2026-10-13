# Trading Journal API

Spring Boot 4.1.1 backend for the trading-journal PoC. Requires Java 25 and Docker.

## Run locally

1. Install Java 25 and start Docker Desktop. Verify both are available:

   ```powershell
   java -version
   docker info
   ```

2. Start the PostgreSQL 18 database:

   ```powershell
   docker compose up -d postgres
   docker compose ps
   ```

   Wait until `postgres` reports `healthy`. The database listens only on
   `127.0.0.1:5432`. Its development-only default credentials are
   `trading_journal` / `local-development-only`. To override them, set
   `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `POSTGRES_DB` before starting Compose.

3. Set development-only API credentials in this PowerShell session, then start
   the API with the `local` profile (which enables Docker Compose integration):

   ```powershell
   $env:SPRING_SECURITY_USER_NAME = "local-user"
   $env:SPRING_SECURITY_USER_PASSWORD = "local-password"
   .\mvnw.cmd -Plocal spring-boot:run -Dspring-boot.run.profiles=local
   ```

4. In a second PowerShell window, verify the authenticated health endpoint:

   ```powershell
   curl.exe -u "local-user:local-password" http://localhost:8080/actuator/health
   ```

   Use the same credentials set in step 3. A successful response contains
   `"status":"UP"`. Stop the API with `Ctrl+C`.

Flyway owns the schema. Hibernate validates it at startup and must not create or
update tables. Springdoc is disabled by default; enable the `dev` profile to
expose `/v3/api-docs` and Swagger UI.

Run backend tests with:

```powershell
.\mvnw.cmd verify
```

## Eightcap Trades Report PoC

The authenticated principal name is mapped to a user row. Create accounts with
`POST /api/v1/trading-accounts`; account names, trades, closes, cash transactions,
and import history are scoped to that user. For local API testing, configure
`SPRING_SECURITY_USER_NAME` and `SPRING_SECURITY_USER_PASSWORD` before startup.
The default Spring Security user is for development only.

Create an Eightcap account:

```json
{
  "name": "A",
  "broker": "Eightcap",
  "currency": "USD",
  "brokerLogin": "optional broker login",
  "tradesReportUtcOffsetMinutes": 180,
  "defaultRiskMode": "FIXED_AMOUNT",
  "defaultRiskValue": 10
}
```

Preview then confirm the same `.xlsx` or `.csv` file using multipart field `file`.
The CSV and XLSX readers support the Eightcap report-title preamble and require
the 14 report columns. Uploads are limited to 10 MB and 100,000 data rows.

```text
POST /api/v1/trading-accounts/{accountId}/imports/eightcap/preview
POST /api/v1/trading-accounts/{accountId}/imports/eightcap
```

Send the preview's `fileSha256` as `sha256` to confirm. If preview reports
`loginMismatch`, send `acknowledgeLoginMismatch=true` to continue. A duplicate
rate above 10% adds a non-blocking monthly-export warning. Malformed reports
return `ProblemDetail` errors with row numbers and make no writes.

Trade totals are available through
`GET /api/v1/trading-accounts/summary?accountIds={id1},{id2}`. Trades Report close
times remain broker-local unless the account has an explicit UTC offset.
Set or change an existing account's fallback rule with
`PUT /api/v1/trading-accounts/{accountId}/risk-rule`; this recalculates R for
already imported trades. Send both `defaultRiskMode` and `defaultRiskValue`, or
send both as `null` to clear the rule.

Report-based daily, monthly, and total P&L/R are available through
`GET /api/v1/trading-accounts/summary/performance?accountIds={id1},{id2}`.
Daily and monthly dates use the account owner's timezone. The response separates
trades with and without calculated R and reports closes without a resolved UTC
timestamp, so missing report data is not presented as a valid zero.

R uses each imported close's net P&L divided by the position's monetary risk.
The calculator prefers one unambiguous linked stop order, then the account's
default risk rule. `FIXED_AMOUNT` is currency risk per position;
`FIXED_STOP_DISTANCE` is price distance multiplied by report-inferred value per
price unit and opened volume. A stop on the profitable side, less than half the
median adverse-side stop distance for that symbol, or multiple linked stop
prices sets `SL_MAY_HAVE_MOVED`. The calculator avoids using flagged stops and
uses the account rule if configured. If the report cannot establish risk, R
remains unavailable and the response reports that trade under `tradesWithoutR`.

## TradingView Order History PoC

TradingView imports accept CSV files with the 15 Order History columns. Preview
then confirm with the returned `fileSha256`:

```text
POST /api/v1/trading-accounts/{accountId}/imports/tradingview/preview
POST /api/v1/trading-accounts/{accountId}/imports/tradingview
```

All order statuses are retained for audit; only filled orders form positions and
closes. Order IDs are idempotent within an account, and reusing an ID with changed
row data returns a conflict. Position IDs link to Eightcap tickets by their
numeric suffix. Trades Report values and closes take precedence regardless of
import order; TradingView-only positions retain each filled partial close.
TradingView timestamps are interpreted as UTC+7.
