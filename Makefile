# =============================================================================
# Banking Playground — convenience targets
# =============================================================================
COMPOSE := docker compose -f docker-compose.yml
MVN     := ./mvnw -ntp
KUBECTL := kubectl
K8S_OVERLAY := deploy/k8s/overlays/local

.PHONY: help up down logs ps build it e2e-local mongo-up mongo-down status reset k8s-up k8s-down k8s-status

help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

up: ## Build images and start the whole stack (infra + 3 apps)
	$(COMPOSE) up -d --build

down: ## Stop and remove containers (volumes preserved)
	$(COMPOSE) down

reset: ## Stop and remove containers AND volumes (fresh DB next time)
	$(COMPOSE) down -v

logs: ## Tail logs from the migration worker
	$(COMPOSE) logs -f migration-worker

ps: ## Show container status
	$(COMPOSE) ps

status: ## Show the loader's progress and both inventory sizes
	@echo "--- worker status ---";   curl -s http://localhost:8087/migration/status; echo
	@echo "--- legacy entries ---"; curl -s http://localhost:8085/admin/stats;      echo
	@echo "--- new entries ---";    curl -s http://localhost:8086/customer-product-and-service-directory/stats; echo

build: ## Compile + unit tests (no Docker)
	$(MVN) verify

# --- Local Kubernetes (see deploy/README.md and docs/04-kubernetes-aks) -------
k8s-up: ## Deploy the stack to the current kube-context (kind/minikube/Docker Desktop)
	$(KUBECTL) apply -k $(K8S_OVERLAY)

k8s-down: ## Remove the stack from the cluster
	-$(KUBECTL) delete -k $(K8S_OVERLAY)

k8s-status: ## Show pods + services in the bank namespace
	@$(KUBECTL) get pods,svc -n bank

it: ## Run the in-JVM end-to-end test (needs Docker API >= 1.44 / Docker 25+)
	$(MVN) -Pit verify

# Run the E2E against an externally-managed MongoDB (works on older Docker where
# Testcontainers cannot negotiate the API version). Starts/stops a throwaway mongo.
e2e-local: mongo-up ## Run the E2E against a CLI-managed MongoDB on :27019
	$(MVN) -Pit -pl e2e-tests verify -De2e.mongo.uri=mongodb://localhost:27019 ; \
	$(MAKE) mongo-down

mongo-up:
	-docker run -d --rm -p 27019:27017 --name bank-e2e-mongo mongo:7.0
	@sleep 4

mongo-down:
	-docker rm -f bank-e2e-mongo
