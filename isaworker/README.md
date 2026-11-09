# isaworker

External task workers for the multi-tenant Fluxnova server (`../fluxnova-multitenant`). isaworker talks to the Fluxnova
engines only over their REST API, runs the workers that execute their service tasks, and keeps what people enter in its own
PostgreSQL database `workerdb`. The dialogs ("Please enter your name:") open on **your Windows desktop**, not in the container.

## Architecture: four containers, two database servers that never meet

```
 Windows desktop
 ┌───────────────────────────┐                      docker network "api-net"
 │ dialog agent              │──── HTTP (localhost:8181, token) ────┐
 │ run-dialog-agent.ps1      │                                      ▼
 │  real Swing windows       │          ┌─────────────────────────────────────────────┐   REST: fetchAndLock,
 └───────────────────────────┘          │ isaworkercontainer  (the workers, JPA)      │◄──── complete, failure ────┐
                                        └──────────────┬──────────────────────────────┘                            │
                                      worker-db-net    │                                                           │
                              ┌────────────────────────▼────────────┐                                              │
                              │ workerPostgresClusterDB  (:5432)    │      ┌─────────────────────────────────────┐ │
                              │   workerdb = BUSINESS data          │      │ fluxnova-application  (:8080)       │─┘
                              │   (table worker_detail)             │      │  one engine per tenant (REST API)   │
                              └─────────────────────────────────────┘      └──────────────┬──────────────────────┘
                                                                              fluxnova-db-net │
                                                                       ┌──────────────────────▼──────────────┐
                                                                       │ fluxnovadb  (:5433 on the host)     │
                                                                       │   flux_<tenant>_db = Fluxnova's own │
                                                                       │   data, NO business data            │
                                                                       └─────────────────────────────────────┘
```

| Container | Role | Database server it uses |
|---|---|---|
| `fluxnova-application` | the Fluxnova engine: one process engine per tenant, built from `../fluxnova-multitenant` | `fluxnovadb` (the tenant databases are created there) |
| `isaworkercontainer` | the external task workers: fetch tasks, ask the person, write `workerdb`, complete tasks | `workerPostgresClusterDB` (database `workerdb`) |
| `fluxnovadb` | PostgreSQL 16.15 for Fluxnova's own use only | |
| `workerPostgresClusterDB` | PostgreSQL 16.15 for the business data | |

The Docker networks enforce the separation: `fluxnova-application` cannot reach `workerPostgresClusterDB`, and
`isaworkercontainer` cannot reach `fluxnovadb`.

## Start it

```powershell
cd C:\Users\rajivnayan.verma\Fluxnova\isaworker
docker compose up -d --build          # the four containers (builds ..\fluxnova-multitenant and isaworker)
.\run-dialog-agent.ps1                # on the desktop: shows the dialogs; leave this window open
```

**BPMN models are deployed by the Fluxnova container, not by isaworker** (isaworker never deploys anything).
`fluxnova-application` **watches the folder `C:\Users\rajivnayan.verma\bpmn_deployment`** (`FLUXNOVA_BPMN_DIR` in `.env`,
mounted - writable - at `/config/bpmn_deployment`). **Every tenant has its own sub-folder in it**, named after the tenant
and created by `fluxnova-application` as soon as the tenant is onboarded (`bpmn_deployment\tenant-c\`): copy a `.bpmn`
file into **the sub-folder of the tenant that is to run it** and, within a few seconds, it is deployed to **that tenant's
engine only** (no other tenant sees it) and **one instance of its process is started**. The three dialogs then appear on
your desktop, one after the other (run `.\run-dialog-agent.ps1` first). A `.bpmn` file directly in `bpmn_deployment`
belongs to no tenant and is ignored (with a warning in the log).

Onboarding a tenant starts nothing: a file that is already in a tenant's sub-folder when its engine starts (the tenant was
just onboarded or put back, or the container was restarted) is deployed - so the process can be started by REST or from the
portal - but no instance is started. Only a file that lands, or changes, afterwards runs. Offboarding a tenant removes its
engine and database, not its sub-folder or the files in it. A model that was already deployed with identical content is not
deployed again; changing the file deploys a new version and starts a new instance.

The script is still there to deploy a model by hand from the folder `bpmn\`:

```powershell
.\deploy-bpmn.ps1 -Tenant tenant-c -Start   # deploys bpmn\worker-details-process.bpmn to tenant-c and starts one instance
```

`.\deploy-bpmn.ps1` without `-Start` only deploys; `-File <name>.bpmn` deploys another model from `bpmn\`; `-Tenant` picks
the tenant. By hand, instead of the script:

```powershell
curl.exe -u admin:<password> -F "deployment-name=worker-details" -F "worker-details-process.bpmn=@bpmn\worker-details-process.bpmn" http://localhost:8080/engine-rest/engine/tenant-x/deployment/create
curl.exe -u admin:<password> -X POST -H "Content-Type: application/json" -d "{}" http://localhost:8080/engine-rest/engine/tenant-x/process-definition/key/workerDetailsProcess/start
```

## The process and its workers

[`bpmn/worker-details-process.bpmn`](bpmn/worker-details-process.bpmn):
`Start → enter_name → enter_address → show_details → End`, every task an **external task**.

| Task (id)       | Topic                | Worker               | What it does                                                                                              |
|-----------------|----------------------|----------------------|-----------------------------------------------------------------------------------------------------------|
| `enter_name`    | `enter-name-topic`   | `EnterNameWorker`    | dialog "Please enter your name:" → new `WorkerDetail` row (process instance id + name) in `workerdb`     |
| `enter_address` | `enter-address-topic`| `EnterAddressWorker` | dialog "Please enter your address:" → updates the row of the instance in `workerdb`                       |
| `show_details`  | `show-details-topic` | `ShowDetailsWorker`  | reads the row from `workerdb` (Spring Data JPA) → shows name and address in a **read-only** dialog        |

**The business data stays out of Fluxnova's database.** The workers write the answers to `workerdb` only and set **no process
variables**: a process variable would be stored by the engine in `fluxnovadb`, which is for Fluxnova's use only
(`isaworker.workers.set-process-variables=false`, the default; `ISAWORKER_SET_PROCESS_VARIABLES=true` switches the variables
on if the process itself ever has to read the values).

A dialog that is cancelled or closed does not lose the task: the worker sends a **failure report** to the engine
(`handleFailure`, message "The name dialog was cancelled or closed without an answer", 3 tries, 30 s apart). When the tries
are used up the engine raises an **incident**, which an operator sees (and resolves) in the Fluxnova Monitoring portal.
The same happens when nobody answers within 10 minutes (the message then says whether the agent was connected).

The older demo process (`bpmn/demo-process.bpmn`, topics `return-name` / `return-address`) still works with its two
unchanged workers; deploy it the same way: `.\deploy-bpmn.ps1 -File demo-process.bpmn -Start`.

### `workerdb`: table `worker_detail`

| Column                | Meaning                                                                                              |
|-----------------------|------------------------------------------------------------------------------------------------------|
| `id`                  | generated key                                                                                        |
| `tenant_id`           | the tenant (engine). **Not in the process**: every tenant numbers its process instances on its own  |
| `process_instance_id` | the process instance in that tenant's engine                                                         |
| `name`, `address`     | what the person entered                                                                              |

Unique key: (`tenant_id`, `process_instance_id`). Hibernate creates and extends the table (`ddl-auto=update`).
Look at it: `docker exec -it workerPostgresClusterDB psql -U postgres_admin -d workerdb -c "select * from worker_detail;"`
(Fluxnova's own data: `docker exec -it fluxnovadb psql -U postgres_admin -d flux_tenant_x_db`.)

## Dialogs on the desktop, not in the container

A container has no screen, and a window can only open on the machine that has one. So the worker in the container never
shows anything itself (`isaworker.gui.mode=remote`):

1. the worker needs an answer and **queues the dialog** (and waits, up to 10 minutes);
2. the **dialog agent** (`run-dialog-agent.ps1`, a small Swing program on the desktop) asks the container for work:
   `GET http://localhost:8181/api/dialogs/next` (long polling), shows the dialog as a real window (always on top);
3. the agent sends the answer back (`POST /api/dialogs/{id}/answer`, or `/cancel`, `/ack`), and the worker carries on.

The agent connects **to** the container (which publishes port 8181 on this machine's loopback address only); nothing on the
desktop listens for connections and no firewall rule is needed. Every call carries the shared secret
`ISAWORKER_DIALOG_TOKEN` (`.env`, same value in the container and for the agent); without it nobody could answer the dialogs,
and the worker refuses to start in remote mode without a token. The agent can be started before or after the container and
the process; a dialog that is already waiting appears as soon as it runs. `/actuator/health` shows `dialogs.agentConnected`.
`-Ui console` makes the agent ask on the console instead of in windows (SSH sessions, scripted answers).

Other modes (`ISAWORKER_GUI_MODE`): `auto` (Swing windows in the JVM itself if it has a desktop, else headless), `swing`,
`headless` (no windows; the answers come from process variables `name` / `address` supplied when the process is started).

## Configuration

| File | What |
|---|---|
| `src/main/resources/application.properties` | the **`workerdb` connection**, the HikariCP pool and the JPA / Hibernate settings (`ddl-auto=update`) |
| `src/main/resources/application.yml` | the workers (lock duration, long polling, back-off), the dialogs (`isaworker.gui.*`) |
| `fluxnova-engine.yml` (external, watched: a change restarts the container) | **the Fluxnova engine endpoints**: one entry per tenant, see below |
| `tenants.yml` of the Fluxnova container (folder `FLUXNOVA_TENANTS_DIR` in `.env`, here `C:\Users\rajivnayan.verma\fluxconfig`; default `topology/fluxnova-config/`) | which tenants the Fluxnova container serves, and its database server `fluxnovadb` (`host: "fluxnovadb"`, `admin-database: "fluxnovadb"`). Watched: a tenant added is onboarded, a tenant removed is offboarded |
| `.env` | passwords and the dialog token (never committed) |

### The Fluxnova engine endpoints: `fluxnova-engine.yml`

**isaworker does not onboard or offboard tenants.** Which tenants exist (engine created or removed, database created or
dropped) is decided by the Fluxnova container, `fluxnova-multitenant`, which watches its own `tenants.yml`
(`tenants.yml` in `FLUXNOVA_TENANTS_DIR`, see the table above). `fluxnova-engine.yml` only tells the workers which engine
endpoints to serve.

* The jar contains a **blank** `fluxnova-engine.yml` (`fluxnova-workers.tenants: []`). No tenant is baked into the image.
* The container reads the external file `C:\Users\rajivnayan.verma\tenant-endpoint\fluxnova-engine.yml` (mounted at
  `/config`) when it starts, and opens one worker per tenant entry.
* **The file is watched, and a change restarts the container.** Every 5 s the application looks at the file. When the
  endpoints (or any other setting) in it differ from the ones it started with, it ends itself with exit code 75 after
  stopping its workers in an orderly way, and Docker's restart policy (`restart: unless-stopped`) starts the same container
  again within seconds, which reads the file afresh. No `docker restart` by hand. The restart is the *only* way a change is
  applied; the running workers are never changed in place. Things to know:
  * An edit that changes no setting (a comment, spaces, the order of the keys) restarts nothing.
  * A file that is still being saved is not acted on: a change must be seen unchanged by two looks in a row (5-10 s).
  * An invalid file (YAML error, wrong value) restarts nothing; the workers run on, and the reason is logged
    (`Ignoring invalid ...`) and shown in `/actuator/health` (`config.lastError`). Fixing the file restarts the container.
  * A restart interrupts a dialog that is open on your desktop: the task is offered again after its lock runs out (up to 15
    minutes) or when you start the process again.
  * Without a restart policy (the jar started by hand) the application would stop and stay stopped: set
    `ISAWORKER_RESTART_ON_CHANGE=false` there.
* **Zero tenants is a normal state.** If the file is missing, empty, or lists no tenant, the application does not fail: it
  logs `No active Fluxnova engine configured. Application is running in idle mode.`, keeps running, and `/actuator/health`
  reports `UP` with `mode: idle`.
* A file that cannot be read or parsed (YAML error, wrong value) is not applied either: the application starts idle, logs
  the reason (`Ignoring invalid ...`) and shows it in `/actuator/health` (`config.error`).
* A tenant entry that cannot be used (no `id`, no valid `baseUrl`, duplicate `id`) is skipped with an error in the log; the
  other tenants still run.

```yaml
fluxnova-workers:
  tenants:
    - id: "tenant-x"
      baseUrl: "http://fluxnova-application:8080/engine-rest/engine/tenant-x"
```

Fluxnova's REST API requires HTTP Basic credentials: `FLUXNOVA_REST_USER` / `FLUXNOVA_REST_PASSWORD` (used for every
tenant), or `username` / `password` on a tenant entry.

### Database connection and pool (`application.properties`)

`WORKERDB_URL` (default `jdbc:postgresql://localhost:5432/workerdb`; in the container
`jdbc:postgresql://workerPostgresClusterDB:5432/workerdb`), `WORKERDB_USER`, `WORKERDB_PASSWORD` (no default).
HikariCP: 10 connections at most, 2 kept warm, 20 s wait for a free one, connections recycled after 30 min, idle ones
probed every 5 min, and at start-up up to 60 s of retries while the database container is still starting. The workers keep
a connection only for the length of a query, never while a dialog is open.
isaworker needs `workerdb` at start-up: without it the application does not start (the container restarts until the
database is up). "No tenant configured" is unrelated: that still idles.

## Behaviour worth knowing

* One `ExternalTaskClient` (own polling thread, credentials and back-off) per tenant; one tenant's engine being down never
  slows another tenant.
* **Lock time.** A person needs longer than the 30 s default to type, so the three interactive topics lock a fetched task
  for 15 min (`isaworker.gui.lock-duration`); otherwise the engine could hand it to another worker and a second dialog
  would open. While a dialog is open the tenant's polling thread waits for the answer (other tenants are not affected).
* Long polling (`async-response-timeout`, 20 s): a new task is picked up immediately; failed polls back off exponentially.
* A failing task is reported with `handleFailure` and retried 3 times; after that the engine raises an incident. Nothing
  escapes into the polling thread.
* The JVM runs in UTC (the PostgreSQL driver sends the JVM time zone; `Asia/Calcutta` is refused by PostgreSQL 16).

## Build and test

```powershell
mvn clean verify      # unit tests, JPA, the dialog bridge and agent, live reload, and the workers against a fake Fluxnova
```

The tests use an in-memory database (profile `test`) and never open windows. The real Swing windows are tested by opt-in
tests that find each dialog on the screen, type into it and press its buttons (they flash windows, so they never run in a
normal build):

```powershell
mvn -Dtest="SwingDialogServiceTest,RemoteBridgeSwingTest" -Disaworker.swing.test=true "-DargLine=-Djava.awt.headless=false" test
```
