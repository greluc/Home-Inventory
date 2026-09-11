# deploy/helm/

The Kubernetes chart.

**Hand-written, not generated** — a chart is too different in shape to derive
from the service matrix. It is **validated** against
[`../services.yaml`](../services.yaml) instead: same services, same environment
keys, same resource limits. A divergence fails CI.

## Why it is maintained at all

Most operators of this system will never need Kubernetes. The chart exists for
two reasons anyway:

1. It is the path to high availability, which Compose and Quadlet do not offer.
2. **It disciplines the architecture.** What runs in Kubernetes is necessarily
   stateless, configured entirely through the environment, and has health
   endpoints that actually work. Keeping the chart honest keeps the application
   honest.

## Rootless applies here too

`runAsNonRoot`, `runAsUser: 10001`, `allowPrivilegeEscalation: false`,
`readOnlyRootFilesystem: true`, `capabilities.drop: [ALL]`,
`seccompProfile: RuntimeDefault`, the namespace on the `restricted` PodSecurity
profile in `enforce` mode, and `hostUsers: false` wherever the cluster version
supports it — the counterpart to rootless Podman (REQ-SEC-087).

`NetworkPolicy` defaults to `deny-all` and then opens specific paths, mirroring
the three network segments. Secrets come from `ExternalSecret` or
`SealedSecret` — never from the chart.

## Tested against

`kind`, in CI, with PodSecurity enabled. A chart that has only ever been rendered
and never run proves nothing.

## Status

Empty. Stage 2.
