# Event Booking System

Java 21 and Spring Boot 3 backend using Maven, PostgreSQL, Flyway, Spring Security, and signed JWT bearer tokens. The application implements account registration/login, event management, public browsing, bookings/cancellation, and asynchronous email through a PostgreSQL transactional outbox.

## Prerequisites

- JDK 21
- Maven 3.9+
- PostgreSQL 15+
- Docker, to run the PostgreSQL Testcontainers integration suite

## Local setup

Create a local database and application login. Set the password in your shell or secrets manager; never commit it.

```sql
CREATE ROLE event_app LOGIN PASSWORD 'choose-a-local-password';
CREATE DATABASE event_booking OWNER event_app;
```

Configure the database and generate a private 256-bit (or stronger) JWT key as Base64. Keep it secret and stable across application instances:

```sh
export DB_URL=jdbc:postgresql://localhost:5432/event_booking
export DB_USERNAME=event_app
export DB_PASSWORD='your-local-password'
export JWT_SECRET_BASE64="$(openssl rand -base64 32)"
mvn spring-boot:run
```

Default profile is `local`; set `SPRING_PROFILES_ACTIVE=prod` for production settings. Flyway applies migrations on startup.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/event_booking` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `event_app` | Database login |
| `DB_PASSWORD` | empty | Database password; supply externally |
| `JWT_SECRET_BASE64` | required | Base64-encoded HMAC signing key; at least 256 bits |
| `JWT_EXPIRATION_SECONDS` | `3600` | Access-token lifetime |
| `BOOKING_MAX_TICKETS_PER_CUSTOMER` | `5` | Active ticket limit per customer per event |
| `DB_POOL_SIZE` | Hikari default; `20` in prod | Production JDBC pool size |
| `SERVER_PORT` | `8080` | HTTP listen port |
| `SPRING_PROFILES_ACTIVE` | `local` | `local`, `test`, or `prod` |
| `SMTP_HOST` | `localhost` | SMTP server hostname from your email provider |
| `SMTP_PORT` | `587` | SMTP submission port |
| `SMTP_USERNAME` | empty | Provider SMTP username |
| `SMTP_PASSWORD` | empty | Provider SMTP credential or app password |
| `SMTP_FROM` | `no-reply@example.invalid` | Verified sender address configured with the provider |
| `SMTP_AUTH` | `true` | Enable SMTP authentication |
| `SMTP_STARTTLS_ENABLE` | `true` | Require STARTTLS for the connection |
| `EMAIL_WORKER_ENABLED` | `true` | Enable the scheduled outbox worker |
| `EMAIL_WORKER_FIXED_DELAY_MS` | `5000` | Delay between worker batches |
| `EMAIL_WORKER_BATCH_SIZE` | `25` | Maximum jobs claimed per batch |
| `EMAIL_WORKER_MAX_ATTEMPTS` | `5` | Maximum provider attempts before a job is dead-lettered |
| `EMAIL_WORKER_INITIAL_BACKOFF_SECONDS` | `30` | Initial retry delay |
| `EMAIL_WORKER_MAX_BACKOFF_SECONDS` | `900` | Maximum exponential retry delay |
| `EMAIL_WORKER_LEASE_SECONDS` | `600` | Claim lease for crash recovery |

Public registration creates `CUSTOMER` accounts only. Provision `EVENT_ORGANIZER` accounts through a trusted operator/database bootstrap process. Generate the `password_hash` with Spring's `BCryptPasswordEncoder(12)` and insert that hash; never insert the plaintext password. Role choice is not accepted from the public registration request.

## Authentication and authorization

Register and log in through `/api/auth`. Login returns a signed JWT to send as `Authorization: Bearer <token>`. Passwords are stored with BCrypt. Event writes require `EVENT_ORGANIZER`; booking and booking reads/cancellation require `CUSTOMER`. Event update and booking retrieval/cancellation additionally scope database queries to the authenticated principal. A customer/user ID in a request body is never used to determine identity.

Unauthenticated calls to protected resources receive `401`; a valid account with the wrong role receives `403`. Errors use a common response containing `timestamp`, `status`, `error`, `message`, `path`, and `validationErrors`.

## API

All request and response bodies are JSON. Event times are ISO-8601 instants in UTC (for example `2030-05-01T18:00:00Z`). Events must start in the future when created or updated. Public listing filters use an inclusive `from`, exclusive `to`, partial case-insensitive `location`, and zero-based pagination (maximum page size 100).

| Method and path | Access | Description |
|---|---|---|
| `POST /api/auth/register` | Public | Register a customer |
| `POST /api/auth/login` | Public | Verify credentials and issue JWT |
| `GET /api/events` | Public | Browse published events; `from`, `to`, `location`, `page`, `size` |
| `GET /api/events/{eventId}` | Public | Event details |
| `POST /api/events` | Organizer | Create a published event |
| `PUT /api/events/{eventId}` | Owning organizer | Update event; capacity may not fall below booked inventory |
| `POST /api/events/{eventId}/bookings` | Customer | Book tickets; requires `Idempotency-Key` header |
| `GET /api/bookings` | Customer | List the authenticated customer's bookings |
| `GET /api/bookings/{bookingId}` | Booking owner | Retrieve one booking |
| `POST /api/bookings/{bookingId}/cancel` | Booking owner | Cancel an eligible booking |
| `GET /api/health` | Public | Simple liveness response |
| `GET /actuator/health` | Public | Spring health probe |

### Examples

Register and log in:

```sh
curl -X POST http://localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"customer@example.com","password":"long-local-password"}'

curl -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"customer@example.com","password":"long-local-password"}'
```

Create and browse events (use an organizer JWT for creation):

```sh
curl -X POST http://localhost:8080/api/events -H 'Authorization: Bearer ORGANIZER_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"name":"Spring Concert","description":"Live music","startsAt":"2030-05-01T18:00:00Z","venue":"Main Hall","location":"Delhi","capacity":250}'

curl 'http://localhost:8080/api/events?from=2030-05-01T00:00:00Z&to=2030-06-01T00:00:00Z&location=Delhi&page=0&size=20'
```

Book, retrieve, and cancel (use a customer JWT):

```sh
curl -X POST http://localhost:8080/api/events/EVENT_UUID/bookings -H 'Authorization: Bearer CUSTOMER_TOKEN' \
  -H 'Idempotency-Key: booking-request-001' -H 'Content-Type: application/json' -d '{"quantity":2}'

curl http://localhost:8080/api/bookings -H 'Authorization: Bearer CUSTOMER_TOKEN'
curl -X POST http://localhost:8080/api/bookings/BOOKING_UUID/cancel -H 'Authorization: Bearer CUSTOMER_TOKEN'
```

## Booking correctness and cancellation rules

Inventory is persisted as event `capacity` and `booked_tickets`; available tickets are derived as their difference. Booking and cancellation execute in database transactions. Booking locks the customer row for idempotency/per-customer-limit checks and the event row to serialize competing inventory changes, then performs a conditional database inventory update guarded by capacity, event state, and start time. The database check constraint independently prevents negative or over-capacity inventory. The configured default limit is five active tickets per customer per event. An idempotency key is unique per customer and bound to the event and quantity; a matching replay returns its original booking, while changed request content returns `409`.

Customers may cancel their own confirmed booking until 48 hours before event start. A repeated cancellation returns the already-cancelled booking without changing inventory a second time. Invalid requests return `400`; missing/non-owned resources return `404`; sold-out, limit, cancellation-window, and key-reuse conflicts return `409`.

## Database migrations

Flyway migration files are in `src/main/resources/db/migration`. V1 defines users, events, bookings, idempotency records, and outbox jobs with foreign keys, checks, unique keys, and query indexes. V2 adds outbox attempt/update timestamps and a lease index. Keep applied migrations immutable and add a new numbered migration for changes. Timestamps use PostgreSQL `TIMESTAMPTZ` and are represented as UTC instants.

## Project structure

```text
src/main/java/com/cactro/events/
  api/                    # REST controllers, validation, API errors
  application/            # Account, event, and booking services
  outbox/                 # SMTP delivery, job claiming, retries, and scheduling
  security/               # JWT parsing/issuance and role configuration
  EventBookingApplication.java
src/main/resources/
  application*.yml        # Common and profile-specific configuration
  db/migration/           # Flyway SQL migrations
src/test/java/            # MVC and PostgreSQL integration tests
```

## Build and tests

```sh
mvn clean verify
```

The API integration tests use Testcontainers and require Docker. The concurrent-booking test has two customers compete for the final ticket and checks that only one booking succeeds and inventory remains at capacity.

## Email delivery and background processing

The application uses Spring's SMTP mail sender. Configure a real provider's SMTP submission host, port, username, password/app password, and a verified `SMTP_FROM` address through environment variables or the deployment secret manager. Keep SMTP credentials out of source control and command history. A typical authenticated TLS configuration is:

```sh
export SMTP_HOST=smtp.your-provider.example
export SMTP_PORT=587
export SMTP_USERNAME='provider-smtp-user'
export SMTP_FROM='verified-sender@your-domain.example'
export SMTP_AUTH=true
export SMTP_STARTTLS_ENABLE=true
# Inject SMTP_PASSWORD from your deployment secret manager; do not paste the credential here.
```

After obtaining a customer JWT, create an event in the future, then book it as the customer. The booking confirmation is queued in the same database transaction as inventory reservation, booking, and idempotency record. The API returns after commit; it does not wait for SMTP. Updating the event similarly writes one notification job per distinct customer with a confirmed booking as part of the event update transaction. Notifications contain the event's updated name, time, and location.

The scheduled worker claims up to `EMAIL_WORKER_BATCH_SIZE` rows using PostgreSQL `FOR UPDATE SKIP LOCKED`, marks them `PROCESSING`, increments attempts, and sets a lease before contacting SMTP outside the claim transaction. Multiple application instances can run the worker concurrently. It marks delivered messages `COMPLETED`; transient provider failures return to `PENDING` with exponential backoff (30, 60, 120, 240 seconds by default) and after five total attempts transition to `DEAD`. Authentication/configuration and malformed-message errors, plus SMTP 5xx responses, are marked dead immediately; SMTP 4xx responses and connection failures retry. Expired worker leases are reclaimed; jobs at the attempt limit are dead-lettered.

Each booking confirmation and each event-update/customer pair has a unique deduplication key. Claiming prevents parallel workers from processing the same live lease and completed rows are not selected again. Delivery is at least once: a process crash after SMTP accepts a message but before the database records completion can cause a retry and a duplicate email. SMTP does not provide a portable idempotency mechanism to close that final gap.

### Verify delivery to a real mailbox

1. Configure SMTP credentials and a verified sender for an email account you control.
2. Start the app with a real PostgreSQL database and `EMAIL_WORKER_ENABLED=true`.
3. Register/log in a customer using that mailbox, provision an organizer, create a future event, and book one ticket.
4. Check the mailbox, including spam/quarantine. The response to booking confirms the booking only; it does not claim email delivery.
5. Update the event and confirm the same customer receives an update message.
6. Check `outbox_job` for `COMPLETED`, `PENDING`, or `DEAD` status and inspect only the safe failure class in `last_error`:

```sql
SELECT id, job_type, status, attempts, created_at, last_attempt_at,
       available_at, completed_at, last_error
FROM outbox_job
ORDER BY created_at DESC
LIMIT 50;
```

### Troubleshooting

- **Jobs remain pending:** confirm worker enabled, app scheduling active, database reachable, and `available_at` has passed. The delay column reflects retry backoff.
- **Jobs become dead:** check `last_error` for the exception type, then verify SMTP hostname/port, TLS mode, provider username/password, sender verification, and provider account limits. Fix configuration before replaying a job.
- **Connection timeout:** verify network egress to the provider submission port and the configured connection/read/write timeouts.
- **Authentication or sender rejected:** use the provider's SMTP-specific credential or app password; many providers do not accept the normal account password.
- **No email arrives although the job is complete:** inspect spam/quarantine and provider delivery logs. `COMPLETED` means SMTP accepted the message, not that the recipient mailbox delivered it.
- **Controlled replay:** after correcting the provider issue, an operator may reset only selected dead jobs to `PENDING`, set `attempts=0`, set `available_at=CURRENT_TIMESTAMP`, clear `locked_until`/`last_error`, and update `updated_at`, preserving the existing deduplication key. Confirm the target recipient and accept the at-least-once duplicate risk first.

## Current limitations

- No payment gateway or temporary unpaid holds are implemented.
- SMTP acceptance is not proof of final mailbox delivery; no provider webhook or bounce processing is configured.
- Organizer provisioning requires a trusted operator/bootstrap action; there is no public organizer signup.
- The access token is stateless and has no refresh-token or revocation endpoint.
