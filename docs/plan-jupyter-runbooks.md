# Plan: Jupyter Runbooks — executable business scenarios + debugging toolbox

> **Status:** implementation plan (not yet implemented). Written to be executed by an AI agent or a
> developer, step by step, on **this repo** — but the design is **generic** for any application stack
> with **Java 25 · Spring Boot 4 · Temporal · Kafka · Postgres · a seedable emulator (MongoDB-backed,
> with REST endpoints that emulate dependencies)**. Where this repo lacks a piece today (e.g. Kafka),
> the helper is still specified so the pattern transfers; mark it *optional* during implementation.

## 0. Intent

Two kinds of notebooks, one shared Python helper library, one environment-profile mechanism:

1. **Scenario runbooks** (`runbooks/scenarios/`) — executable specifications of business flows.
   An architect starts one with markdown only (business description of each step). A developer then
   adds code cells that trigger the system (REST, Kafka, Temporal), fetch evidence (logs, DB rows)
   and assert the resulting data state. Rendered output (pandas DataFrames) is the shared,
   human-readable record of "how the system behaved". These run **top-to-bottom in CI** on a
   schedule — that is what keeps them alive instead of rotting.
2. **Toolbox notebooks** (`runbooks/toolbox/`) — small, single-purpose, ad-hoc debugging tools
   ("send exactly this Kafka event", "fetch logs of pod X", "inspect this customer's directory
   entries"). **Never run in CI.** Used interactively, typically against a non-prod AKS environment.

**Design decision — how AKS debugging relates to scenario runbooks** (this was an open question;
the resolution is): scenario runbooks are **environment-agnostic**. They take a single `env`
parameter (papermill-injectable) and every helper resolves endpoints/credentials from
`runbooks/environments/<env>.yaml`. The *same* scenario notebook therefore runs against the local
Docker Compose stack in CI and against non-prod AKS from a laptop — no AKS-specific sections inside
scenario notebooks. Ad-hoc debugging needs (craft one event, tail one pod) are *not* scenarios and
get their own separate toolbox notebooks. Rationale: mixing "spec" and "debug console" in one file
destroys the restart-and-run-all guarantee that CI relies on, while environment profiles give you
the AKS reuse for free.

## 1. Directory layout (to create)

```
runbooks/
  README.md                  # how to install, run locally, run against AKS, add a scenario
  pyproject.toml             # the runbook_lib package + all pinned dependencies
  jupytext.toml              # pairing config (ipynb <-> py:percent)
  pytest.ini                 # nbmake config (scenarios only, timeout)
  .gitignore                 # out/, .ipynb_checkpoints/, __pycache__/
  environments/
    local.yaml               # endpoints of the docker-compose stack
    aks-dev.yaml             # endpoints/namespaces of the non-prod AKS environment
  runbook_lib/               # importable helper package — ALL reusable code lives here
    __init__.py
    config.py
    http.py
    kafka.py                 # optional in this repo until Kafka exists
    temporal.py
    db.py                    # postgres + mongo
    seed.py
    logs.py
    k8s.py
    assertions.py
    display.py
  scenarios/
    _template.ipynb          # copy-me starting point (+ paired _template.py)
    migration-happy-path.ipynb   # reference scenario for THIS repo (section 9)
  toolbox/
    send-kafka-event.ipynb       # optional until Kafka exists
    trigger-temporal.ipynb
    call-api.ipynb
    fetch-pod-logs.ipynb
    inspect-db.ipynb
    seed-emulator.ipynb
  out/                       # papermill-executed notebooks + HTML (gitignored)
```

Top-level `runbooks/`, **not** inside `e2e-tests/` or `src/test`: runbooks are cross-app, drive the
system strictly from the outside (black box), use a different toolchain (Python), and have a
different lifecycle (scheduled, not per-PR). Keeping them out of the Maven reactor avoids any build
coupling.

## 2. Toolchain and libraries

Python **3.12+**. Manage with `pip install -e runbooks/` (the `pyproject.toml` declares everything)
or `uv` if available. Pin at least the major versions below in `pyproject.toml`:

| Purpose | Library | Notes |
|---|---|---|
| Notebook execution | `jupyterlab`, `ipykernel` | authoring |
| CI execution | `papermill` | parameter injection + saved executed output |
| CI test mode | `nbmake` (pytest plugin) | alternative single-command gate |
| Review-friendly diffs | `jupytext` | pair `.ipynb` with `py:percent` scripts |
| Output hygiene | `nbstripout` | strip outputs before commit (pre-commit hook) |
| HTTP | `httpx` | sync client is fine; timeouts always explicit |
| Kafka | `confluent-kafka` | + `confluent-kafka[avro,schemaregistry]` if Avro is used |
| Temporal | `temporalio` | Python SDK; cross-language: starts/signals/queries Java workflows by name |
| Postgres | `sqlalchemy` + `psycopg[binary]` | with `pandas.read_sql` |
| MongoDB | `pymongo` | direct reads of app/emulator state |
| Dataframes | `pandas` (+ `pyarrow`) | THE display/assert primitive |
| Kubernetes | `kubernetes` | pod discovery, logs, port-forward |
| Azure logs (optional) | `azure-monitor-query`, `azure-identity` | only if AKS ships logs to Log Analytics |
| Config | `pyyaml` | environment profiles |
| Retry/poll | none — implement `poll_until` in `assertions.py` | keep dependencies minimal |

**Important cross-language rule:** the Python side must NOT reuse Java classes or serializers.
Duplication here is deliberate — the notebook validates the *wire contract* (JSON/Avro over
HTTP/Kafka), so a bug in the Java serialization layer cannot hide by round-tripping itself. But be
independent of the Java *code*, not the *contract*: decode Kafka messages against schemas fetched
from the schema registry, validate REST responses against the OpenAPI/JSON Schema if published —
never hand-maintain field lists that can drift.

## 3. Environment profiles (`environments/*.yaml`)

One YAML per target environment. Same keys everywhere; helpers read only from the loaded profile.
`local.yaml` for this repo (values match `docker-compose.yml`):

```yaml
name: local
kind: local            # local | aks   — drives log fetching + port-forward behavior
http:
  legacy_inventory: http://localhost:8085        # mocked-apps (the emulator)
  product_inventory: http://localhost:8086
  migration_worker: http://localhost:8087
temporal:
  address: localhost:7234
  namespace: default
  ui: http://localhost:8234
kafka:                 # optional block; absent when the env has no Kafka
  bootstrap: localhost:9092
  schema_registry: http://localhost:8081
postgres:              # Temporal's store here; generic slot for app Postgres elsewhere
  dsn: postgresql+psycopg://temporal:temporal@localhost:5432/temporal
mongo:
  uri: mongodb://localhost:27018
  databases:
    legacy: legacy_inventory
    target: new_inventory
logs:
  source: docker       # docker | kubectl | azure-monitor
  compose_project: bank-migration
```

`aks-dev.yaml` differs in `kind: aks`, adds a `k8s:` block and switches `logs.source`:

```yaml
name: aks-dev
kind: aks
k8s:
  context: <aks-context-name>       # from `az aks get-credentials`
  namespace: bank
  # service-name:port -> local port used by the port-forward helper
  forwards:
    mocked-apps:8085: 18085
    product-inventory-service:8086: 18086
    migration-worker:8087: 18087
    temporal-frontend:7233: 17233
http:
  legacy_inventory: http://localhost:18085      # via port-forward
  product_inventory: http://localhost:18086
  migration_worker: http://localhost:18087
temporal:
  address: localhost:17233
  namespace: default
mongo:
  uri: ""              # usually NOT reachable from laptop; leave empty -> db helpers raise a clear error
logs:
  source: kubectl      # or azure-monitor if Log Analytics is wired
  # azure_monitor: { workspace_id: "...", cluster: "..." }
```

**No secrets in YAML.** Values like passwords/tokens use `${ENV_VAR}` placeholders; `config.py`
expands them from the process environment (locally via direnv/shell, in CI via CI secrets).

## 4. Helper package contracts (`runbook_lib/`)

Implement exactly these modules and signatures. Every function must have an explicit timeout and
raise loud, descriptive exceptions (notebook cells fail = scenario fails; that is the mechanism).

### `config.py`
- `load_env(name: str) -> Env` — reads `environments/{name}.yaml`, expands `${VAR}` placeholders,
  returns a typed accessor (simple dataclass or attrdict). Raises if the file or a referenced env
  var is missing.
- `Env.require(path: str)` — dotted access (`env.require("kafka.bootstrap")`) that raises
  `"environment 'aks-dev' does not define kafka.bootstrap"` instead of `KeyError`.
- If `env.kind == "aks"`, `load_env` returns an `Env` whose `__enter__/__exit__` (context manager)
  starts/stops the port-forwards declared in `k8s.forwards` via `k8s.py`. Scenario notebooks then
  contain a single `env = load_env(ENV); stack = env.connect()` cell that works for both kinds
  (no-op for local).

### `http.py`
- `client(env, service: str) -> httpx.Client` — base_url from `env.http.<service>`, 10 s default
  timeout, `raise_for_status` on by default via event hook.
- `get_json(env, service, path, **params) -> dict|list`, `post_json(env, service, path, body) -> dict`.
- `to_df(payload, record_path=None) -> pd.DataFrame` — thin wrapper over `pd.json_normalize`.

### `kafka.py`  *(optional until the env defines `kafka:`)*
- `produce(env, topic: str, value: dict, key: str|None = None, headers: dict|None = None,
  value_schema: str|None = None)` — JSON by default; if `value_schema` (Avro subject name) is given,
  fetch the schema from the registry and Avro-serialize. Flush before returning; raise on delivery
  error.
- `consume(env, topic: str, *, group: str|None = None, from_beginning: bool = False,
  max_messages: int = 100, timeout_s: float = 10) -> pd.DataFrame` — columns:
  `partition, offset, timestamp, key, value (decoded dict), headers`. Decode Avro via the registry
  when the message is schema-framed, else JSON.
- `wait_for(env, topic, predicate, timeout_s=30) -> dict` — poll-consume until a message matches;
  raise `TimeoutError` with the messages seen so far in the error text.

### `temporal.py`
Async SDK wrapped for notebook use (`asyncio.run` inside sync facades — notebooks stay sync):
- `start_workflow(env, workflow: str, *args, id: str, task_queue: str) -> handle` — starts a
  **Java-implemented** workflow by its registered name; payloads are plain JSON-able dicts (default
  data converter is JSON on both SDKs).
- `signal(env, workflow_id: str, signal: str, *args)`
- `query(env, workflow_id: str, query: str) -> dict` — e.g. this repo's loader `LoaderStats`.
- `describe(env, workflow_id) -> dict` (status, runId), `result(env, workflow_id, timeout_s)`.
- `list_workflows(env, query: str) -> pd.DataFrame` — visibility list query to a DataFrame.

### `db.py`
- `pg_df(env, sql: str, **params) -> pd.DataFrame` — SQLAlchemy engine per env (cached),
  `pd.read_sql`.
- `mongo_df(env, database_alias: str, collection: str, filter: dict = {}, limit: int = 1000,
  projection: dict|None = None) -> pd.DataFrame` — `database_alias` is a key under
  `env.mongo.databases` (e.g. `legacy`, `target`), keeping notebooks environment-portable.
- `mongo_count(env, database_alias, collection, filter={}) -> int`.
- Raise a clear "not reachable in this environment" error when the profile leaves `mongo.uri` empty.

### `seed.py`
Emulator control — REST first (works in every environment), direct Mongo as fallback:
- `seed(env, service: str, spec: dict) -> dict` — `POST /admin/seed` on the emulator with a payload
  describing what to generate (count, directory refs, …); returns the emulator's response.
- `stats(env, service: str) -> dict` — `GET /admin/stats`.
- `insert_documents(env, database_alias, collection, docs: list[dict])` — direct pymongo insert for
  exact-document seeding when the REST API is too coarse (local only, typically).

### `logs.py`
One facade, three sources, selected by `env.logs.source`:
- `fetch(env, app: str, *, since: str = "10m", grep: str|None = None, limit: int = 2000)
  -> pd.DataFrame` with columns `ts, app, pod, level, logger, message, raw`.
  - `docker` — `docker compose -p <project> logs --since ... <service>` via `subprocess`; parse
    JSON-structured lines when the log line is JSON, else regex `ts level logger - message`,
    else `raw` only.
  - `kubectl` — `k8s.py.pod_logs` for all pods matching the app label; same parsing.
  - `azure-monitor` — `azure-monitor-query` KQL against ContainerLogV2, filtered by cluster,
    namespace, app label; requires `az login` (DefaultAzureCredential).
- `assert_no_errors(env, app, since, allow: list[str] = []) -> pd.DataFrame` — fails the cell if
  ERROR-level records exist (minus allowlisted patterns); returns the offending rows for display.
- Local OTel note: this stack logs structured lines to stdout (collected by docker/kubectl). If an
  OTel collector with a `file`/`debug` exporter is added later, add a fourth source `otlp-file`
  that tails the exporter output — same DataFrame shape; do not build it before it exists.

### `k8s.py`
- `pods(env, app: str) -> list[str]` — by `app.kubernetes.io/name` (fallback `app`) label in
  `env.k8s.namespace`.
- `pod_logs(env, pod: str, since: str, limit: int) -> list[str]`.
- `PortForwards(env)` — context manager that spawns `kubectl port-forward svc/<name> local:remote`
  subprocesses for every entry in `env.k8s.forwards`, waits until each local port accepts a TCP
  connection, and terminates them on exit. Used by `config.Env.connect()`.
- Auth is out of scope of the library: document in `runbooks/README.md` that the user runs
  `az login && az aks get-credentials ... && kubelogin convert-kubeconfig -l azurecli` once;
  helpers just use the named kube context.
- **Kafka on AKS caveat (document, don't solve in code):** `kubectl port-forward` does not work for
  Kafka brokers (advertised listeners point to in-cluster names). For AKS Kafka use either an
  externally exposed listener, or run the producing/consuming step *inside* the cluster
  (`kubectl run kafka-toolbox --image=edenhill/kcat:1.7.1 ...` / `kubectl exec`). The toolbox
  notebook `send-kafka-event.ipynb` should implement the `kubectl exec kcat` path for `kind: aks`.

### `assertions.py`
- `poll_until(fn, *, timeout_s: float, interval_s: float = 2, desc: str) -> Any` — calls `fn` until
  it returns a truthy value; on timeout raises `TimeoutError(f"timed out waiting for {desc}; last={...}")`.
- `assert_df_equal(actual: pd.DataFrame, expected: pd.DataFrame, *, keys: list[str], ignore: list[str] = [])`
  — sort by keys, drop ignored columns, `pandas.testing.assert_frame_equal`, and on failure display
  a joined diff DataFrame before raising.
- `check(condition: bool, msg: str)` — plain assert with message (so cells read as business checks).

### `display.py`
- `step(title: str, description: str = "")` — renders a styled markdown heading from code (used by
  papermill-injected loops if ever needed; scenarios mostly use raw markdown cells).
- `show(df, title=None, max_rows=50)` — consistent truncated rendering.

## 5. Notebook conventions (enforced by review + CI)

1. **Cell 1 is markdown**: scenario title, business goal, owner, links (this is the architect's
   part — a scenario may be committed with markdown only and `raise NotImplementedError` code stubs;
   CI marks it *expected-fail* via a `# SKIP-CI` first-line tag until a developer fills it in).
2. **Cell 2 is the papermill `parameters` cell** (tagged `parameters`):
   ```python
   ENV = "local"          # environment profile name
   RUN_ID = "dev"         # injected in CI: unique per run; used in all created test data
   ```
3. **Cell 3 is setup**: `env = load_env(ENV)`, `env.connect()`, imports from `runbook_lib` only.
4. Every business step = one markdown cell (what & why, business language) + one or more code cells
   (trigger → fetch evidence → `show(...)` → assert). Assertions live in the step, not batched at
   the end.
5. **Idempotency**: all created identifiers embed `RUN_ID` (e.g. customer ref `RB-{RUN_ID}-001`) so
   scheduled runs never collide with yesterday's data and notebooks never depend on each other.
6. **Restart & Run All must pass** before commit — that is the definition of done for any edit.
7. **No secrets in cells or outputs**, ever (outputs are uploaded as CI artifacts).
8. Scenario notebooks must not contain `kind`-conditional logic; if a step can't work in some
   environment, the helper raises its clear error and that scenario simply isn't scheduled for that
   environment.
9. Toolbox notebooks are exempt from 1–5 but keep the parameters cell so they're papermill-able too.

**Version control:** jupytext pairs every notebook with a `py:percent` script (`jupytext.toml`:
`formats = "ipynb,py:percent"`); the `.py` is what humans review in PRs. `nbstripout` as a
pre-commit hook keeps committed `.ipynb` outputs empty. Executed, output-rich notebooks live only
in `runbooks/out/` (gitignored) and CI artifacts.

## 6. Triggering — recipes the template must demonstrate

- **REST**: `post_json(env, "migration_worker", "/migration/start", {})`;
  poll with `poll_until(lambda: get_json(env, "migration_worker", "/migration/status")["pending"] == 0, ...)`.
- **Temporal**: prefer the app's own control plane (REST) when one exists — that tests the app's
  surface. Use `temporal.py` directly to (a) assert workflow state via `query`/`describe`/`list_workflows`,
  (b) send signals a UI would send, (c) start workflows in apps that have no HTTP trigger.
- **Kafka**: `produce(...)` an event exactly as an upstream system would emit it (schema-registry
  serialized), then `wait_for(...)` the downstream event and assert the DB state change.
- **Seeding**: `seed.seed(...)` before the scenario's first step; show `seed.stats(...)` as a
  DataFrame so the starting state is visible in the rendered document.
- **Evidence**: after every state-changing step, fetch at least one of: DB rows (`mongo_df`/`pg_df`),
  emitted events (`consume`), app logs (`logs.fetch` + `assert_no_errors`).

## 7. CI — what keeps runbooks alive

`.github/workflows/runbooks.yml`, triggers: `schedule` (nightly, e.g. `0 3 * * *`) +
`workflow_dispatch` (manual, with an `env` input defaulting to `local`). Jobs:

```yaml
name: runbooks
on:
  schedule: [{cron: "0 3 * * *"}]
  workflow_dispatch:
    inputs: {env: {default: "local"}}
jobs:
  scenarios:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: {python-version: "3.12"}
      - run: pip install -e runbooks/
      - name: Start stack
        run: docker compose up -d --build --wait   # local env only
      - name: Execute scenarios
        run: |
          mkdir -p runbooks/out
          rc=0
          for nb in runbooks/scenarios/*.ipynb; do
            [ "$(basename "$nb")" = "_template.ipynb" ] && continue
            papermill "$nb" "runbooks/out/$(basename "$nb")" \
              -p ENV "${{ inputs.env || 'local' }}" -p RUN_ID "${{ github.run_id }}" \
              --execution-timeout 600 || { echo "FAILED: $nb"; rc=1; }
          done
          jupyter nbconvert --to html runbooks/out/*.ipynb
          exit $rc
      - uses: actions/upload-artifact@v4
        if: always()
        with: {name: runbook-reports, path: runbooks/out/}
      - name: Stack logs on failure
        if: failure()
        run: docker compose logs --since 30m
```

Notes for the implementer:
- `--wait` uses the compose healthchecks already defined in this repo — no sleep loops.
- Keep going after a failed notebook (collect all failures), fail the job at the end.
- The loop deliberately continues past failures so one broken scenario doesn't hide the rest;
  the saved executed notebook of the failure is the debugging artifact.
- Optional PR smoke gate: `pytest --nbmake runbooks/scenarios/migration-happy-path.ipynb`
  behind a `runbooks/**` path filter — only if runtime stays under ~5 min.
- Publishing HTML to GitHub Pages ("how the system behaved last night", readable by
  non-developers) is a later nice-to-have; artifacts are enough for v1.

## 8. AKS usage (interactive, from a laptop)

Document in `runbooks/README.md`:
1. One-time: `az login`, `az aks get-credentials -g <rg> -n <cluster>`,
   `kubelogin convert-kubeconfig -l azurecli`. Fill the context name into `environments/aks-dev.yaml`.
2. `jupyter lab` in `runbooks/`; open any scenario or toolbox notebook; set `ENV = "aks-dev"` in the
   parameters cell; run. `env.connect()` brings up the port-forwards; everything else is identical
   to local.
3. Debugging flow example (the thing this design optimizes for): open
   `toolbox/send-kafka-event.ipynb` → paste/craft the exact event payload → produce it (in-cluster
   via the kcat-exec path) → switch to `toolbox/fetch-pod-logs.ipynb` → `logs.fetch(env, "migration-worker", since="5m", grep=RUN_ID)`
   → inspect the DataFrame; `inspect-db.ipynb` to check resulting records where DB access exists.
4. CI does **not** run against AKS in v1. When wanted later: a second scheduled job with
   `env: aks-dev`, federated credentials (`azure/login`), and only scenarios that don't need direct
   DB access.

## 9. Reference implementation for THIS repo (proves the design)

`scenarios/migration-happy-path.ipynb` — the first real scenario, structured as:

1. *(markdown)* Business goal: a Premier Banking customer's product directory is migrated from the
   legacy core to the modern core without loss.
2. Seed: `seed.seed(env, "legacy_inventory", {...})` a known small dataset tagged with `RUN_ID`
   (extend `mocked-apps` `/admin/seed` if it can't yet create deterministic tagged entries — that
   extension is part of this plan, see task list). Show `seed.stats`.
3. Trigger: `POST /migration/start` on `migration_worker` (idempotent per the control plane).
4. Observe: `poll_until` on `GET /migration/status` (`LoaderStats.pending == 0` and
   `processedCustomers` covering the seeded refs, timeout 120 s); also show
   `temporal.list_workflows(env, "WorkflowType='LoaderWorkflow'")`.
5. Validate data state: `mongo_df(env, "target", "directory_entries", {"...": RUN_ID-tag})` vs the
   seeded legacy rows via `assert_df_equal(keys=[entry ref], ignore=[timestamps, ids])`; counts via
   `GET /customer-product-and-service-directory/stats` on the target.
6. Validate logs: `assert_no_errors(env, "migration-worker", since="15m")`.
7. *(markdown)* Conclusion cell summarizing what a green run proves.

## 10. Implementation task list (ordered; each has acceptance criteria)

1. **Scaffold** `runbooks/` per section 1: `pyproject.toml` (package `runbook_lib`, deps from
   section 2, `[project.optional-dependencies] kafka`, `azure`), `jupytext.toml`, `pytest.ini`,
   `.gitignore`, empty module files. ✅ `pip install -e runbooks/` succeeds; `import runbook_lib` works.
2. **`config.py` + `environments/local.yaml`** per sections 3–4. ✅ unit-testable without any
   services: `load_env("local").require("temporal.address") == "localhost:7234"`; missing key raises
   the descriptive error.
3. **`http.py`, `db.py`, `seed.py`, `assertions.py`, `display.py`** per section 4. ✅ against the
   running compose stack: `get_json(env, "migration_worker", "/migration/status")` returns a dict;
   `mongo_df(env, "legacy", <collection>).shape[0] > 0` after `make up`.
4. **`temporal.py`**. ✅ `list_workflows` returns a DataFrame of the running loader/discovery
   workflows on the compose stack; `query` on the loader workflow id returns `LoaderStats` fields.
5. **`logs.py` (docker source)**. ✅ `fetch(env, "migration-worker", since="5m")` returns a non-empty
   DataFrame with parsed `level`; `assert_no_errors` passes on a healthy stack.
6. **`_template.ipynb`** implementing every convention in section 5, with one dummy step per trigger
   type (REST live; Kafka/AKS steps as markdown-documented stubs where the env lacks them).
   ✅ Restart-and-run-all passes locally; the paired `.py` exists.
7. **Seed determinism**: extend `mocked-apps` `POST /admin/seed` to accept
   `{count, directoryRef, tag}` and stamp `tag` on generated entries (or document the existing
   equivalent if present). ✅ seeding twice with different tags yields disjoint, queryable sets.
8. **`scenarios/migration-happy-path.ipynb`** per section 9. ✅ passes with
   `papermill ... -p ENV local -p RUN_ID test1` twice in a row (idempotency), and after
   `docker compose down -v && up` (freshness).
9. **CI workflow** per section 7. ✅ manual `workflow_dispatch` run is green; artifacts contain
   executed `.ipynb` + `.html`; a deliberately broken assertion turns the job red and the artifact
   shows the failing cell.
10. **`k8s.py` + `environments/aks-dev.yaml` + toolbox notebooks** (`trigger-temporal`, `call-api`,
    `fetch-pod-logs`, `inspect-db`, `seed-emulator`; `send-kafka-event` when Kafka exists) per
    sections 4, 8. ✅ against the local `deploy/k8s` kind/minikube stack (same K8s API as AKS):
    `env.connect()` port-forwards, the happy-path scenario passes with `ENV=aks-dev`-style profile
    pointed at the local cluster; `logs.source: kubectl` returns pod logs.
11. **`kafka.py` + Avro path** — implement when a Kafka-bearing app lands in the repo; the module
    contract in section 4 is the spec. Until then it raises `NotImplementedError("no kafka in env")`.
12. **`runbooks/README.md`** covering: install, run a scenario locally, author a new scenario
    (architect flow: markdown-first + `# SKIP-CI`; developer flow: fill code, remove tag), AKS
    setup (section 8), conventions checklist (section 5). ✅ a newcomer can run the reference
    scenario following only this README.
13. **Docs integration**: add a `docs/05-runbooks/README.md` section page linking here and to
    `runbooks/README.md`, and add the row to `docs/README.md`'s navigation table.

**Definition of done for the whole plan:** nightly CI executes the reference scenario green and
uploads readable HTML; an architect can add a markdown-only scenario without breaking CI; a
developer can debug against AKS using only toolbox notebooks + environment profile, without editing
any scenario.
