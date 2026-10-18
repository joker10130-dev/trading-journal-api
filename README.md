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

3. Create a development-only JWT signing key, then start the API with the `local`
   profile (which enables Docker Compose integration):

   ```powershell
   $bytes = New-Object byte[] 32
   $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
   $rng.GetBytes($bytes)
   $rng.Dispose()
   $env:JWT_SECRET_BASE64 = [Convert]::ToBase64String($bytes)
   .\mvnw.cmd -Plocal spring-boot:run -Dspring-boot.run.profiles=local
   ```

4. In a second PowerShell window, create a development user and use its short-lived
   access token to call an authenticated API:

   ```powershell
   $session = curl.exe -s -c cookies.txt -X POST http://localhost:8080/api/v1/auth/sign-up `
     -H "Content-Type: application/json" `
     -d '{"email":"local-user@example.test","password":"development-password"}' | ConvertFrom-Json
   curl.exe -H "Authorization: Bearer $($session.accessToken)" http://localhost:8080/api/v1/trading-accounts
   ```

   Access tokens expire after 10 minutes. A refresh token is issued as an
   HttpOnly, SameSite=Strict cookie. Keep the signing key private and use a
   managed secret in production. Stop the API with `Ctrl+C`.

## Authentication

Sign up and sign in with JSON fields `email` and `password`:

```text
POST /api/v1/auth/sign-up
POST /api/v1/auth/sign-in
POST /api/v1/auth/refresh
POST /api/v1/auth/sign-out
```

Sign-up is open for the MVP and requires a password of at least 10 characters.
Sign-in failures use a generic message and are rate-limited after five failures
in 15 minutes. Access tokens are returned in the JSON response and sent as
`Authorization: Bearer <accessToken>`. Refresh tokens are random, stored only as
hashes, rotated on refresh, and revoked on sign-out. Password reset is omitted
until an email service is available.

Flyway owns the schema. Hibernate validates it at startup and must not create or
update tables. Springdoc is disabled by default; enable the `dev` profile to
expose `/v3/api-docs` and Swagger UI.

Run backend tests with:

```powershell
.\mvnw.cmd verify
```

## Eightcap Trades Report PoC

JWT subjects are registered user emails. Create, rename and list trading accounts
with `POST`, `PUT /api/v1/trading-accounts/{accountId}` and
`GET /api/v1/trading-accounts`; accounts, trades, closes, cash transactions and
import history are scoped to that user.
Sign-up is open for the MVP; passwords must be at least 10 characters. Password
reset is not available because no email service is configured.

Create an Eightcap account:

```json
{
  "name": "A",
  "broker": "Eightcap",
  "currency": "USD",
  "brokerLogin": "optional broker login",
  "tradesReportUtcOffsetMinutes": 180
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

Daily, monthly, and total P&L/R are available through
`GET /api/v1/trading-accounts/summary/performance?accountIds={id1},{id2}`.
Daily and monthly dates use the account owner's timezone. The response separates
trades with and without manually entered R and reports closes without a resolved UTC
timestamp, so missing report data is not presented as a valid zero.

Create a closed manual trade with `POST /api/v1/trading-accounts/{accountId}/trades`.
Times are local to the user's timezone. An optional `rMultiple` is entered
directly by the user. Manual closes are included in the same performance
summaries as imported closes.

R is never calculated from imported files. Set or edit it with
`PUT /api/v1/trades/{tradeId}/r` and `{"rMultiple": 1.5}`; send
`{"rMultiple": null}` to clear it. R is split across a trade's closes by volume
for performance totals. Values with `|R| > 5` receive the informational
`R_ABSOLUTE_OVER_5` flag without being changed.

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
