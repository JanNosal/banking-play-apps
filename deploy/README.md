# Local Kubernetes deployment (`deploy/`)

> Run the whole stack on a **local Kubernetes cluster** to learn the Kubernetes/AKS object model with
> these apps. The K8s API here is identical to AKS, so ~95% of what you learn transfers. For the staged
> path to *real* AKS and the Azure-only concepts, see
> [docs/04-kubernetes-aks](../docs/04-kubernetes-aks/README.md).

## What's here

```
deploy/
  k8s/
    base/                      # Kustomize base: namespace, config, mongo, temporal(+pg+ui),
                               #   the 3 apps (Deployment+Service), ingress
    overlays/local/            # the overlay you apply (smaller seed)
  helm/temporal-values.yaml    # optional: official Temporal Helm chart instead of the auto-setup Pod
```

Objects map 1:1 to the [docker-compose](../docker-compose.yml) stack; see
[docs/01-architecture](../docs/01-architecture/README.md) for what each app does.

## Prerequisites

- A local cluster: **Docker Desktop Kubernetes** (Settings → Kubernetes → Enable), or **kind**
  (`kind create cluster`), or **minikube** (`minikube start`).
- `kubectl` (and `kustomize`, bundled into `kubectl -k`).
- The app images built locally: `make up` once (or `docker compose build`) produces
  `bank/mocked-apps:0.0.1`, `bank/product-inventory-service:0.0.1`, `bank/migration-worker:0.0.1`.

## Run it

```bash
# 1. Build the images (once).
make up && make down            # builds bank/*:0.0.1 (then stops the compose stack)

# 2. kind ONLY — load the local images into the cluster (Docker Desktop/minikube can skip this):
for a in mocked-apps product-inventory-service migration-worker; do \
  kind load docker-image bank/$a:0.0.1; done
#   minikube alternative: `minikube image load bank/<app>:0.0.1`

# 3. Deploy.
make k8s-up                     # = kubectl apply -k deploy/k8s/overlays/local
make k8s-status                 # pods + services in the bank namespace
kubectl get pods -n bank -w     # watch them go Ready (worker waits for temporal + both cores)

# 4. Reach the endpoints (no ingress needed — port-forward):
kubectl -n bank port-forward svc/migration-worker 8087:8087 &
kubectl -n bank port-forward svc/product-inventory-service 8086:8086 &
kubectl -n bank port-forward svc/temporal-ui 8233:8233 &
curl -s localhost:8087/migration/status
curl -s localhost:8086/customer-product-and-service-directory/stats
open http://localhost:8233      # Temporal UI

# 5. Tear down.
make k8s-down                   # = kubectl delete -k deploy/k8s/overlays/local
```

The migration auto-starts (`AUTO_START=true`, `MAX_PASSES=-1`), just like the compose stack: watch the
target count climb to the migrated subset of the seeded legacy data.

## Optional: ingress instead of port-forward

`deploy/k8s/base/ingress.yaml` defines host-based routing (the two cores share URL paths, so routing is
by host). Install an ingress controller first, e.g.:
```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
# then: curl -H 'Host: worker.bank.localhost' http://localhost/migration/status
```

## What to try (learning exercises)

- Scale a core: `kubectl -n bank scale deploy/product-inventory-service --replicas=3` and watch the
  Service load-balance.
- Break and self-heal: `kubectl -n bank delete pod -l app=migration-worker` — the Deployment recreates it
  and Temporal resumes the workflow.
- Tune via config: edit `deploy/k8s/base/config.yaml` (`SEED_CUSTOMERS`, `MAX_PASSES`) and re-apply.
- Add an HPA (needs metrics-server) on a core and drive load.
- Swap Temporal to the Helm chart (`deploy/helm/temporal-values.yaml`).

## Notes / honest limitations

- MongoDB runs **without auth** (matching the compose stack). In a real cluster, move credentials to a
  `Secret`/Key Vault and enable auth — see [docs/04-kubernetes-aks](../docs/04-kubernetes-aks/README.md).
- This is a single-replica learning setup (Mongo/Temporal are not HA). It teaches the K8s model, not a
  production topology.
