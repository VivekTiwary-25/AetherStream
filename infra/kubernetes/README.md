# Kubernetes demo runtime

Adds a Kubernetes deployment alongside the unchanged root Docker Compose demo. Namespace: `aether`.
This is a single-node development baseline, not an HA or internet-facing production installation.

## Components

- Kafka 4.3.2: single-node KRaft StatefulSet, headless Service, persistent data, idempotent six-partition `truck-events` init Job.
- PostgreSQL 17: StatefulSet, 2 GiB PVC, schema ConfigMap generated directly from `infra/db/schema.sql` (never copied).
- MinIO: Deployment with `Recreate` strategy, 2 GiB PVC, idempotent checkpoint-bucket init Job.
- Coordinator: one replica, `Recreate` strategy (no simultaneous active coordinators), port 8081, process health probes. Service-link environment injection is disabled so Kubernetes cannot replace the numeric `COORDINATOR_PORT` default with a `tcp://` URL; service discovery uses DNS.
- Workers: three StatefulSet pods, `worker-0`, `worker-1`, `worker-2`; downward API sets `WORKER_ID` to the pod name. The runtime treats same-ID re-registration with owned partitions as recovery.
- Simulator, control API and web: Deployments and Services. API readiness checks dependencies; liveness only checks the process. Web NodePort is 30088; nginx proxies `/api` and `/ws` unchanged.

All images use `IfNotPresent`. Application images default to the published GHCR SHA in `kustomization.yaml`;
set all four to the same desired published commit before deployment. `GIT_SHA=dev` is the contract default;
override it alongside image tags to report the deployed revision. Contract 00 non-secret environment values
are in `configmap.yaml`; credentials are only in `secret.example.yaml`.

**Development credentials are public and applied by default.** For any non-local use, replace the example
Secret through a private overlay, restrict network access, configure TLS/authentication, and supply suitable
storage. Do not commit real credentials. PVCs use the cluster's default StorageClass (kind supplies one).
Budget about 6 GiB of node memory for this demo; the Java processes have explicit memory limits.

## Deploy (Bash, from repository root)

Prerequisites: Docker, kubectl, kind 0.30.0, Kustomize 5.8.1 and kubeconform 0.7.0.
Use Kubernetes 1.34.0 for parity with CI. A local kind cluster with web access:

```bash
kind create cluster --name aether --image kindest/node:v1.34.0 --config=- <<'YAML'
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 30088
        hostPort: 8088
        listenAddress: "127.0.0.1"
YAML
```

For a different published image revision, run this before rendering:

```bash
SHA=<published-commit-sha>
(cd infra/kubernetes
 for image in engine simulator control-api web; do
   kustomize edit set image "ghcr.io/vivektiwary-25/aetherstream-$image=ghcr.io/vivektiwary-25/aetherstream-$image:$SHA"
 done
 kustomize edit add configmap aether-config --behavior merge --from-literal="GIT_SHA=$SHA")
```

The schema generator intentionally references `../db/schema.sql`. Plain `kubectl apply -k infra/kubernetes`
rejects files outside the kustomization root; its embedded Kustomize does not expose a load-restrictor flag.
Render with standalone Kustomize, validate, then apply the rendered directory using kubectl:

```bash
rendered=$(mktemp -d)
kustomize build --load-restrictor LoadRestrictionsNone infra/kubernetes > "$rendered/resources.yaml"
kubeconform -strict -summary -kubernetes-version 1.34.0 "$rendered/resources.yaml"
printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources: [resources.yaml]\n' > "$rendered/kustomization.yaml"
kubectl apply -k "$rendered"
kubectl -n aether wait --for=condition=complete jobs --all --timeout=600s
for workload in $(kubectl -n aether get deployments,statefulsets -o name); do
  kubectl -n aether rollout status "$workload" --timeout=600s
done
kubectl -n aether wait pods --all --field-selector=status.phase!=Succeeded --for=condition=Ready --timeout=600s
rm -rf "$rendered"
kubectl -n aether port-forward svc/control-api 18080:8080
# Another terminal:
curl -fsS http://127.0.0.1:18080/api/ready
```

Open <http://127.0.0.1:8088>. Without the kind port mapping, use
`kubectl -n aether port-forward svc/web 8088:80` instead.

Postgres initialization runs only on an empty PVC; changing the generated ConfigMap does not migrate an
existing database. Jobs are one-shot and idempotent; to rerun initialization after changing their template,
delete the named init Jobs and reapply the rendered deployment. Never delete PVCs to perform a schema migration.
Deleting the namespace or kind cluster destroys this local demo's data; keep production backups separately.

## Local images and CI acceptance

For local builds, build all four Dockerfiles and load them into the named cluster, then replace GHCR images
with `aether/<name>:ci` before rendering:

```bash
for image in engine simulator control-api web; do
  docker build -f "infra/docker/$image.Dockerfile" -t "aether/$image:ci" .
done
kind load docker-image --name aether aether/engine:ci aether/simulator:ci aether/control-api:ci aether/web:ci
(cd infra/kubernetes
 for image in engine simulator control-api web; do
   kustomize edit set image "ghcr.io/vivektiwary-25/aetherstream-$image=aether/$image:ci"
 done)
# Run the render/validate/apply commands above.
```

`.github/workflows/ci-k8s.yml` runs for matching pushes on `main` and `task/**`, matching pull requests to
`main`, or `workflow_dispatch`. It validates the rendered manifests with kubeconform **before** building
four local images, creating kind, loading images and applying the deployment. All init Jobs and long-running
pods must become ready within a shared ten-minute deadline.

The smoke step checks `/api/ready` is `UP`, exercises the actual web NodePort and nginx API proxy, saves
and starts the demo pipeline through the API, and waits for three assigned workers, a completed checkpoint
and nonempty window output. It records the generation, checkpoint and `worker-1` pod UID, deletes that pod,
and requires `/coord/state` to show the same execution RUNNING at a newer generation, restored from at least
the recorded checkpoint, covering all six partitions within 60 seconds. It also verifies a replacement pod
UID, a newer checkpoint and increasing window output after recovery. Pods, events, descriptions, current
and previous container logs are dumped even on failure; the CI cluster is always removed.

Dispatch after the swarm has committed and pushed this workflow to its task branch:

```bash
gh workflow run ci-k8s.yml --ref task/t261119-0209-622f
gh run list --workflow ci-k8s.yml --branch task/t261119-0209-622f
```

A green `ci-k8s` run on that branch is the acceptance evidence; local schema validation alone is not.
