[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › **Kubernetes & AKS**

# Kubernetes & AKS

> **Read this when** you want to run this stack on Kubernetes and learn Azure Kubernetes Service (AKS) —
> what you can do locally for free, and what genuinely needs Azure.

## In one line

The apps are already containerized with `/actuator/health` probes and env-var config, so they drop onto
Kubernetes cleanly. A **local cluster runs the same Kubernetes API as AKS** (≈95% transferable); the
Azure-specific ≈5% you exercise cheaply on the free tier. Ready-to-apply manifests live in
[`deploy/`](../../deploy/README.md).

## How the repo maps onto Kubernetes

| Repo piece | Kubernetes object (in `deploy/k8s/base`) | On real AKS you'd likely… |
|------------|------------------------------------------|---------------------------|
| `mocked-apps` / `product-inventory-service` / `migration-worker` | `Deployment` + `Service` (+ optional `Ingress`) | keep as-is; probes already hit `/actuator/health` |
| MongoDB | `StatefulSet` + `PVC` (`mongo.yaml`) | swap for **Cosmos DB for MongoDB** (managed) |
| Temporal + Postgres + UI | `Deployment`/`StatefulSet` (`temporal.yaml`) or the [Helm chart](../../deploy/helm/temporal-values.yaml) | keep, or use **Temporal Cloud** |
| compose env vars | `ConfigMap` (`config.yaml`) | source from a `Secret` / Key Vault + Workload Identity |

The compose→K8s translation is direct because everything was already image + env-var driven (see
[Configuration](../01-architecture/configuration.md)).

## What's scaffolded here (local, free)

[`deploy/`](../../deploy/README.md) has a **Kustomize base + `local` overlay**. Quick path:

```bash
make up && make down                 # build bank/*:0.0.1 images once
# kind only: kind load docker-image bank/<app>:0.0.1   (Docker Desktop/minikube skip)
make k8s-up                          # kubectl apply -k deploy/k8s/overlays/local
make k8s-status                      # pods + services in the bank namespace
kubectl -n bank port-forward svc/migration-worker 8087:8087   # then curl /migration/status
make k8s-down
```

This teaches Deployments, Services, ConfigMaps, StatefulSets/PVCs, probes, init-container ordering,
Ingress, and scaling — the entire core model, identical to AKS.

## Staged path to real AKS

1. **Local cluster (done here).** Docker Desktop K8s / kind / minikube + the `deploy/` manifests.
2. **AKS-shape it locally.** Add an NGINX ingress; package as Helm/Kustomize overlays. **Draft**
   (`az aks draft create`) can auto-generate Dockerfiles, manifests, Helm charts, and a GitHub Actions
   workflow from the apps — [Draft for AKS](https://learn.microsoft.com/azure/aks/draft).
3. **Real AKS on the free credit.** Provision with Bicep/Terraform or `az aks create`; push images via
   **ACR** (`az acr build`); `az aks get-credentials`; deploy the same manifests. Control plane is free
   (Free tier); pay only for node VMs — `az aks stop`/delete between sessions.
   [Prepare app](https://learn.microsoft.com/azure/aks/tutorial-kubernetes-prepare-app) →
   [Deploy to AKS](https://learn.microsoft.com/azure/aks/tutorial-kubernetes-deploy-application) ·
   [Training module](https://learn.microsoft.com/training/modules/aks-deploy-container-app/).
4. **CI/CD + inner loop.** GitHub Actions for AKS (`azure/acr-build`, `azure/aks-set-context`,
   `azure/k8s-deploy`) — [GH Actions for AKS](https://learn.microsoft.com/azure/aks/kubernetes-action);
   develop locally against a remote cluster with
   [Telepresence](https://learn.microsoft.com/azure/aks/use-telepresence-aks) or Bridge to Kubernetes.

## The AKS-only ≈5% (learnable only on Azure)

Workload Identity (vs. secrets), Azure CNI networking, the **Application Routing add-on** (managed NGINX
ingress — [docs](https://learn.microsoft.com/azure/aks/app-routing)), Container Insights / Azure Monitor,
ACR integration, cluster autoscaler / node pools, Azure RBAC. To *simulate the infrastructure
provisioning* without spending, use `bicep what-if` / `terraform plan`.

## Reuse in your own project (similar stack)

1. Expose a **health endpoint** and drive all config via **env vars** — then K8s manifests are mechanical.
2. Learn the **core model on a free local cluster**; only go to AKS for the Azure-native features.
3. Keep manifests in **Kustomize base + overlays** (`local`, later `aks`) so one base serves every target.
4. Use **Draft / azd** to bootstrap, then own the YAML.

## See also

- [`deploy/README.md`](../../deploy/README.md) — the run guide and learning exercises.
- [Architecture](../01-architecture/README.md) — what each app/Service does.
- [Build & Run › Docker & Compose](../03-build-and-run/docker-and-compose.md) — the images K8s runs.
