#!/bin/bash

# Progressive Delivery Platform Validation Script
# This script validates the deployment of all components in the progressive delivery platform

# Note: We don't use 'set -e' to allow the script to continue even if checks fail

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Counters
PASSED=0
FAILED=0
WARNINGS=0

# Helper functions
print_header() {
    echo -e "\n${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}\n"
}

print_success() {
    echo -e "${GREEN}✓${NC} $1"
    ((PASSED++))
}

print_error() {
    echo -e "${RED}✗${NC} $1"
    ((FAILED++))
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
    ((WARNINGS++))
}

print_info() {
    echo -e "${BLUE}ℹ${NC} $1"
}

# Check if a namespace exists
check_namespace() {
    local ns=$1
    if oc get namespace "$ns" &>/dev/null; then
        print_success "Namespace '$ns' exists"
        return 0
    else
        print_error "Namespace '$ns' not found"
        return 1
    fi
}

# Check if pods in a namespace are running
check_pods_running() {
    local ns=$1
    local label=$2
    local min_count=${3:-1}
    
    local pod_count=$(oc get pods -n "$ns" -l "$label" --field-selector=status.phase=Running --no-headers 2>/dev/null | wc -l)
    
    if [ "$pod_count" -ge "$min_count" ]; then
        print_success "Found $pod_count running pod(s) in '$ns' with label '$label'"
        return 0
    else
        print_error "Expected at least $min_count running pod(s) in '$ns' with label '$label', found $pod_count"
        return 1
    fi
}

# Check if a CRD exists
check_crd() {
    local crd=$1
    if oc get crd "$crd" &>/dev/null; then
        print_success "CRD '$crd' exists"
        return 0
    else
        print_error "CRD '$crd' not found"
        return 1
    fi
}

# Check if an operator is installed
check_operator() {
    local ns=$1
    local name=$2
    
    if oc get csv -n "$ns" 2>/dev/null | grep -q "$name"; then
        # Get the first matching CSV and check its phase
        local phase=$(oc get csv -n "$ns" -o json 2>/dev/null | jq -r ".items[] | select(.metadata.name | contains(\"$name\")) | .status.phase" | head -1)
        if [ "$phase" = "Succeeded" ]; then
            print_success "Operator '$name' is installed and ready in '$ns'"
            return 0
        else
            print_warning "Operator '$name' exists but phase is '$phase'"
            return 1
        fi
    else
        print_error "Operator '$name' not found in '$ns'"
        return 1
    fi
}

# Main validation
print_header "Progressive Delivery Platform Validation"
print_info "Starting validation at $(date)"
print_info "Cluster: $(oc whoami --show-server)"
print_info "User: $(oc whoami)"

# Phase 1: OpenShift GitOps Validation
print_header "Phase 1: OpenShift GitOps"

check_namespace "openshift-gitops"
check_operator "openshift-operators" "openshift-gitops-operator"
check_pods_running "openshift-gitops" "app.kubernetes.io/name=openshift-gitops-server" 1
check_pods_running "openshift-gitops" "app.kubernetes.io/name=openshift-gitops-repo-server" 1
check_pods_running "openshift-gitops" "app.kubernetes.io/name=openshift-gitops-application-controller" 1

# Check ArgoCD CRDs
check_crd "applications.argoproj.io"
check_crd "applicationsets.argoproj.io"
check_crd "appprojects.argoproj.io"

# Check ArgoCD route
if oc get route openshift-gitops-server -n openshift-gitops &>/dev/null; then
    ARGOCD_URL=$(oc get route openshift-gitops-server -n openshift-gitops -o jsonpath='{.spec.host}')
    print_success "ArgoCD UI available at: https://$ARGOCD_URL"
else
    print_error "ArgoCD route not found"
fi

# Phase 2: Service Mesh Validation
print_header "Phase 2: Red Hat OpenShift Service Mesh"

check_namespace "istio-system"
check_namespace "openshift-operators-redhat"
check_namespace "openshift-distributed-tracing"

check_operator "openshift-operators-redhat" "servicemeshoperator"
check_operator "openshift-operators-redhat" "kiali-operator"
check_operator "openshift-distributed-tracing" "jaeger-operator"

# Check Service Mesh Control Plane
if oc get smcp -n istio-system &>/dev/null; then
    SMCP_STATUS=$(oc get smcp -n istio-system -o jsonpath='{.items[0].status.conditions[?(@.type=="Ready")].status}')
    if [ "$SMCP_STATUS" = "True" ]; then
        print_success "Service Mesh Control Plane is ready"
    else
        print_error "Service Mesh Control Plane is not ready"
    fi
else
    print_error "Service Mesh Control Plane not found"
fi

# Check Istio pods
check_pods_running "istio-system" "app=istiod" 1
check_pods_running "istio-system" "app=istio-ingressgateway" 1
check_pods_running "istio-system" "app=istio-egressgateway" 1

# Check Kiali
if oc get route kiali -n istio-system &>/dev/null; then
    KIALI_URL=$(oc get route kiali -n istio-system -o jsonpath='{.spec.host}')
    print_success "Kiali UI available at: https://$KIALI_URL"
else
    print_warning "Kiali route not found"
fi

# Check Jaeger
if oc get route jaeger -n istio-system &>/dev/null; then
    JAEGER_URL=$(oc get route jaeger -n istio-system -o jsonpath='{.spec.host}')
    print_success "Jaeger UI available at: https://$JAEGER_URL"
else
    print_warning "Jaeger route not found"
fi

# Phase 3: Argo Rollouts Validation
print_header "Phase 3: Argo Rollouts"

check_namespace "argo-rollouts"
check_crd "rollouts.argoproj.io"
check_crd "analysistemplates.argoproj.io"
check_crd "analysisruns.argoproj.io"
check_crd "experiments.argoproj.io"

# Check RolloutManager
if oc get rolloutmanager -n argo-rollouts &>/dev/null; then
    ROLLOUT_COUNT=$(oc get rolloutmanager -n argo-rollouts --no-headers 2>/dev/null | wc -l)
    if [ "$ROLLOUT_COUNT" -gt 0 ]; then
        ROLLOUT_PHASE=$(oc get rolloutmanager -n argo-rollouts -o jsonpath='{.items[0].status.phase}' 2>/dev/null)
        if [ "$ROLLOUT_PHASE" = "Available" ]; then
            print_success "RolloutManager is available"
        elif [ -z "$ROLLOUT_PHASE" ]; then
            print_warning "RolloutManager exists but status is not yet available (may still be initializing)"
        else
            print_warning "RolloutManager phase is '$ROLLOUT_PHASE'"
        fi
    else
        print_error "RolloutManager not found"
    fi
else
    print_error "RolloutManager CRD or resource not found"
fi

check_pods_running "argo-rollouts" "app.kubernetes.io/name=argo-rollouts" 1

# Check for metric plugin
if oc get cm argo-rollouts-config -n argo-rollouts &>/dev/null; then
    if oc get cm argo-rollouts-config -n argo-rollouts -o yaml | grep -q "metricProviderPlugins"; then
        print_success "Metric provider plugins configured"
    else
        print_warning "Metric provider plugins not configured in ConfigMap"
    fi
else
    print_warning "argo-rollouts-config ConfigMap not found"
fi

# Phase 4: GitOps Configuration Validation
print_header "Phase 4: GitOps Configuration"

# Check AppProjects
if oc get appproject system -n openshift-gitops &>/dev/null; then
    print_success "AppProject 'system' exists"
else
    print_error "AppProject 'system' not found"
fi

if oc get appproject workloads -n openshift-gitops &>/dev/null; then
    print_success "AppProject 'workloads' exists"
else
    print_error "AppProject 'workloads' not found"
fi

# Check ApplicationSets
SYSTEM_APPSET=$(oc get applicationset -n openshift-gitops -l purpose=system --no-headers 2>/dev/null | wc -l)
if [ "$SYSTEM_APPSET" -gt 0 ]; then
    print_success "System ApplicationSet exists"
else
    print_warning "System ApplicationSet not found"
fi

WORKLOAD_APPSET=$(oc get applicationset -n openshift-gitops -l purpose=workloads --no-headers 2>/dev/null | wc -l)
if [ "$WORKLOAD_APPSET" -gt 0 ]; then
    print_success "Workloads ApplicationSet exists"
else
    print_warning "Workloads ApplicationSet not found"
fi

# Check Applications
print_info "Checking ArgoCD Applications..."
APP_COUNT=$(oc get application -n openshift-gitops --no-headers 2>/dev/null | wc -l)
if [ "$APP_COUNT" -gt 0 ]; then
    print_success "Found $APP_COUNT ArgoCD Application(s)"
    
    # Check application health
    HEALTHY=$(oc get application -n openshift-gitops -o jsonpath='{.items[?(@.status.health.status=="Healthy")].metadata.name}' 2>/dev/null | wc -w)
    SYNCED=$(oc get application -n openshift-gitops -o jsonpath='{.items[?(@.status.sync.status=="Synced")].metadata.name}' 2>/dev/null | wc -w)
    
    print_info "  - Healthy: $HEALTHY/$APP_COUNT"
    print_info "  - Synced: $SYNCED/$APP_COUNT"
    
    if [ "$HEALTHY" -eq "$APP_COUNT" ] && [ "$SYNCED" -eq "$APP_COUNT" ]; then
        print_success "All applications are healthy and synced"
    else
        print_warning "Some applications are not healthy or synced"
        echo -e "\n${YELLOW}Application Status:${NC}"
        oc get application -n openshift-gitops -o custom-columns=NAME:.metadata.name,HEALTH:.status.health.status,SYNC:.status.sync.status
    fi
else
    print_warning "No ArgoCD Applications found"
fi

# Optional: Canary Application Validation
print_header "Optional: Canary Application (if deployed)"

if oc get namespace canary-app &>/dev/null; then
    print_success "Namespace 'canary-app' exists"
    # Check if namespace is part of service mesh
    if oc get smmr -n istio-system -o yaml | grep -q "canary-app"; then
        print_success "Namespace 'canary-app' is part of Service Mesh"
    else
        print_warning "Namespace 'canary-app' is not part of Service Mesh"
    fi
    
    # Check for Rollout
    if oc get rollout -n canary-app &>/dev/null; then
        ROLLOUT_COUNT=$(oc get rollout -n canary-app --no-headers | wc -l)
        print_success "Found $ROLLOUT_COUNT Rollout(s) in 'canary-app'"
        
        # Check rollout status
        oc get rollout -n canary-app -o custom-columns=NAME:.metadata.name,STATUS:.status.phase,REPLICAS:.status.replicas
    else
        print_info "No Rollouts found in 'canary-app'"
    fi
    
    # Check for VirtualService
    if oc get virtualservice -n canary-app &>/dev/null; then
        print_success "VirtualService configured in 'canary-app'"
    else
        print_info "No VirtualService found in 'canary-app'"
    fi
    
    # Check for Gateway
    if oc get gateway -n canary-app &>/dev/null; then
        print_success "Gateway configured in 'canary-app'"
    else
        print_info "No Gateway found in 'canary-app'"
    fi
else
    print_info "Canary application namespace not found (optional component)"
fi

# Summary
print_header "Validation Summary"

TOTAL=$((PASSED + FAILED + WARNINGS))
echo -e "${GREEN}Passed:${NC}   $PASSED"
echo -e "${RED}Failed:${NC}   $FAILED"
echo -e "${YELLOW}Warnings:${NC} $WARNINGS"
echo -e "Total:    $TOTAL"

if [ $FAILED -eq 0 ]; then
    echo -e "\n${GREEN}✓ Validation completed successfully!${NC}"
    EXIT_CODE=0
else
    echo -e "\n${RED}✗ Validation completed with $FAILED failure(s)${NC}"
    EXIT_CODE=1
fi

# Next Steps
print_header "Next Steps"

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}Your progressive delivery platform is ready!${NC}\n"
    echo "1. Access ArgoCD UI:"
    echo "   URL: https://$ARGOCD_URL"
    echo "   Login: Use OpenShift credentials or get admin password with:"
    echo "   oc get secret openshift-gitops-cluster -n openshift-gitops -o jsonpath='{.data.admin\.password}' | base64 -d"
    echo ""
    echo "2. Access Kiali (Service Mesh Console):"
    [ -n "$KIALI_URL" ] && echo "   URL: https://$KIALI_URL"
    echo ""
    echo "3. Deploy a canary application:"
    echo "   oc apply -k progressive-delivery/workloads/canary-app/"
    echo ""
    echo "4. Monitor rollouts:"
    echo "   kubectl argo rollouts get rollout <rollout-name> -n <namespace> --watch"
    echo ""
    echo "5. View rollout dashboard:"
    echo "   kubectl argo rollouts dashboard"
else
    echo -e "${RED}Please fix the failed checks before proceeding.${NC}\n"
    echo "Common troubleshooting steps:"
    echo "1. Check operator installation status:"
    echo "   oc get csv -A"
    echo ""
    echo "2. Check pod logs for errors:"
    echo "   oc logs -n <namespace> <pod-name>"
    echo ""
    echo "3. Check ArgoCD application status:"
    echo "   oc get application -n openshift-gitops"
    echo ""
    echo "4. Sync applications manually if needed:"
    echo "   oc patch application <app-name> -n openshift-gitops --type merge -p '{\"operation\":{\"initiatedBy\":{\"username\":\"admin\"},\"sync\":{\"revision\":\"HEAD\"}}}'"
fi

echo ""
print_info "Validation completed at $(date)"

exit $EXIT_CODE

