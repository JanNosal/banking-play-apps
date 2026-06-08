[🏠 Repo](../README.md) › **📖 Documentation**

# Documentation Hub

> **For humans and AI assistants.** This tree explains *how this repository is implemented* and *how to
> reuse each pattern* in a different project on a similar stack (**Spring Boot 4 · Temporal · MongoDB ·
> Testcontainers · virtual threads · synchronous `RestClient`**). Every page links to the real source.
>
> **AI usage:** to answer "how did this repo do X?", find X in the map below, open that page, then open
> the source files it cites. To *create* tests, also read [`.github/copilot-instructions.md`](../.github/copilot-instructions.md)
> (the imperative test-authoring guide).

This repo is a **banking playground**: its first experiment is a Temporal-orchestrated **migration** of
a customer's [BIAN](https://bian.org) *Customer Product and Service Directory* from a legacy core to a
modern core. See the [root README](../README.md) for the elevator pitch.

## Navigation (root → section → page)

### 1. [Architecture](01-architecture/README.md) — what the system is and how it's built
| Page | Answers |
|------|---------|
| [Data model](01-architecture/data-model.md) | How the BIAN domain records are modelled (and why `record`s). |
| [Services & REST APIs](01-architecture/services-and-apis.md) | The three apps, their endpoints, pagination contract. |
| [Temporal workflows](01-architecture/temporal-workflows.md) | Discovery + loader: signals, queries, dedup queue, bounded concurrency, continue-as-new. |
| [Persistence & MongoDB](01-architecture/persistence-and-mongodb.md) | Documents, indexes, repositories, idempotent upsert. |
| [Concurrency & virtual threads](01-architecture/concurrency-and-virtual-threads.md) | Where virtual threads are used and why it's safe with Temporal. |
| [Data seeding](01-architecture/data-seeding.md) | The deterministic generator that makes runs reproducible. |
| [Configuration](01-architecture/configuration.md) | Typed properties, env-var mapping, precedence. |
| [Observability](01-architecture/observability.md) | Health/metrics, the migration control plane, the Temporal UI. |

### 2. [Testing](02-testing/README.md) — the three-layer E2E strategy
| Page | Answers |
|------|---------|
| [Strategy / the pyramid](02-testing/README.md) | Why three layers and what each uniquely catches. |
| [Layer 1 · Workflow-logic tests](02-testing/workflow-logic-tests.md) | Fast control-flow tests with mocked activities. |
| [Layer 2 · In-JVM E2E](02-testing/in-jvm-e2e-tests.md) | Real apps + Testcontainers Mongo + real workflows. |
| [Layer 3 · Dockerised E2E](02-testing/dockerised-e2e-tests.md) | The real Temporal server via `ComposeContainer`. |
| [Reliability & Temporal testing](02-testing/reliability-and-temporal-testing.md) | Bounded waits, time-skipping, timeouts, idempotency. |

### 3. [Build & Run](03-build-and-run/README.md) — toolchain, images, operations
| Page | Answers |
|------|---------|
| [Local development](03-build-and-run/local-development.md) | Maven reactor, BOM layout, Makefile targets. |
| [Docker & Compose](03-build-and-run/docker-and-compose.md) | Multi-stage image, the stack, `.dockerignore`. |
| [Troubleshooting](03-build-and-run/troubleshooting.md) | The fresh-machine landmines (Docker API, `container_name`, buildx, Boot-4 beans). |

### 4. [Kubernetes & AKS](04-kubernetes-aks/README.md) — run on a cluster, learn AKS
| Page | Answers |
|------|---------|
| [Kubernetes & AKS](04-kubernetes-aks/README.md) | repo→K8s object mapping, the local `deploy/` manifests, the staged path to real AKS, and the Azure-only concepts. |

## How these docs are kept current

When code changes, refresh the docs with the **`/refresh-docs`** command (backed by the **`doc-sync`**
skill in [`.claude/skills/doc-sync`](../.claude/skills/doc-sync/SKILL.md)). It re-scans the repo and
updates pages **in place**, preserving the structure, breadcrumbs, source references, and the
"explain + how to reuse" intent.

## Doc conventions (so every page is predictable)

- **Breadcrumb** on line 1, linking each upper level (`🏠 Repo › 📖 Docs › Section › **Page**`).
- Each page has: **Read this when** → **In one line** → **How it's implemented here** (with source
  links) → **Why / key decisions** → **Reuse in your own project** → **See also**.
- Cite source as `path/File.java → Class.method()`. The source is the source of truth; docs explain and
  generalize.
