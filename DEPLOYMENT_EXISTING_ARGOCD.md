# Deploying Progressive Delivery on a Cluster with an Existing Argo CD

This document provides a reproducible deployment path for clusters that already have a shared OpenShift GitOps / Argo CD installation.

Use this flow when the cluster already contains an `ArgoCD` instance such as `openshift-gitops` and you do **not** want this repository to install or manage:
- the OpenShift GitOps operator
- the `openshift-gitops` `ArgoCD` custom resource

## Why this deployment mode exists

The default one-command install in [`README.md`](README.md) applies [`bootstrap/overlays/default`](bootstrap/overlays/default/kustomization.yaml), which includes:

- the OpenShift GitOps operator [`Subscription`](bootstrap/base/openshift-gitops-operator.yaml)
- an [`ArgoCD`](bootstrap/overlays/default/openshift-gitops-argocd.yaml) named `openshift-gitops`
- the shared [`ApplicationSet`](components/applicationsets/system-appset.yaml) and [`AppProject`](components/appprojects/system-project.yaml) resources

On a cluster where `openshift-gitops` already exists, reapplying the operator and the `ArgoCD` resource is risky because the existing instance may be managed by another platform component.

## What this overlay installs

The existing-Argo-CD overlay at [`bootstrap/overlays/existing-argocd/kustomization.yaml`](bootstrap/overlays/existing-argocd/kustomization.yaml) installs only:

- [`ApplicationSet`](bootstrap/overlays/existing-argocd/system-appset-existing-argocd.yaml) for the system components
- [`ApplicationSet`](components/applicationsets/workloads-appset.yaml) for the workload components
- [`AppProject`](components/appprojects/system-project.yaml) and [`AppProject`](components/appprojects/workloads-project.yaml)
- the RBAC policy from [`bootstrap/overlays/existing-argocd/openshift-gitops-rbac-policy.yaml`](bootstrap/overlays/existing-argocd/openshift-gitops-rbac-policy.yaml)



It does **not** install:
- [`Subscription`](bootstrap/base/openshift-gitops-operator.yaml)
- [`ArgoCD`](bootstrap/overlays/default/openshift-gitops-argocd.yaml)

## Prerequisites

Before using this mode, verify the cluster already has:

- an `ArgoCD` instance in the `openshift-gitops` namespace
- the `ApplicationSet` CRD
- the `AppProject` CRD
- the `RolloutManager` CRD

Example verification:

```sh
oc get argocd -A
oc api-resources | grep -E 'applicationsets|appprojects|rolloutmanagers'
```

## One-command install

Apply the bootstrap overlay that reuses the existing Argo CD:

```sh
until oc apply -k bootstrap/overlays/existing-argocd/; do sleep 15; done
```

This is the equivalent of the default README install, but without attempting to install or replace the cluster-wide GitOps control plane.

## What happens after bootstrap

Once the bootstrap resources are created in `openshift-gitops`, the existing Argo CD instance will discover and reconcile the generated applications from:

- [`bootstrap/overlays/existing-argocd/system-appset-existing-argocd.yaml`](bootstrap/overlays/existing-argocd/system-appset-existing-argocd.yaml)
- [`components/applicationsets/workloads-appset.yaml`](components/applicationsets/workloads-appset.yaml)

Those generated applications then deploy the system and workload components from this repository without trying to install the GitOps operator or replace the existing `ArgoCD` resource.

## Required repository customization

Before bootstrapping, update the `repoURL` values in:

- [`bootstrap/overlays/existing-argocd/system-appset-existing-argocd.yaml`](bootstrap/overlays/existing-argocd/system-appset-existing-argocd.yaml)
- [`components/applicationsets/workloads-appset.yaml`](components/applicationsets/workloads-appset.yaml)

Point both files to your fork or the repository URL you want Argo CD to reconcile.

## Configure secrets

After the initial bootstrap completes and the `openshift-gitops` namespace is available, create the Kubernetes agent secret:

```sh
cp system/kubernetes-agent/secret.yaml.template system/kubernetes-agent/secret.yaml
```

Edit [`system/kubernetes-agent/secret.yaml`](system/kubernetes-agent/secret.yaml) and add the required credentials.

Apply it:

```sh
oc apply -f system/kubernetes-agent/secret.yaml
```

## Verification

Check that the bootstrap resources exist:

```sh
oc get applicationset -n openshift-gitops
oc get appproject -n openshift-gitops
```

Check that the generated applications are present:

```sh
oc get application.argoproj.io -n openshift-gitops
```

Check that Argo Rollouts and the Kubernetes agent are running:

```sh
oc get pods -n openshift-gitops | grep argo-rollouts
oc get pods -n openshift-gitops | grep kubernetes-agent
```

Check that the `RolloutManager` exists:

```sh
oc get rolloutmanager -n openshift-gitops
```

## Recommended operational model

Use this mode as the default on any shared or centrally managed cluster.

Use the default overlay in [`bootstrap/overlays/default`](bootstrap/overlays/default/kustomization.yaml) only when you explicitly want this repository to install and manage its own GitOps control plane.