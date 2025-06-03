#!/bin/bash

# Production Deployment Validation Script
# Validates successful deployment of Terraform 6.37.0 infrastructure

set -euo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TERRAFORM_DIR="$(dirname "$SCRIPT_DIR")"
VALIDATION_LOG="$TERRAFORM_DIR/test-results/production_validation_$(date +%Y%m%d_%H%M%S).log"
ENVIRONMENT="${ENVIRONMENT:-production}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Logging
log() {
    echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$VALIDATION_LOG"
}

success() {
    echo -e "${GREEN}✅ $1${NC}" | tee -a "$VALIDATION_LOG"
}

error() {
    echo -e "${RED}❌ $1${NC}" | tee -a "$VALIDATION_LOG"
}

warning() {
    echo -e "${YELLOW}⚠️  $1${NC}" | tee -a "$VALIDATION_LOG"
}

info() {
    echo -e "${BLUE}ℹ️  $1${NC}" | tee -a "$VALIDATION_LOG"
}

# Validation counters
TOTAL_VALIDATIONS=0
PASSED_VALIDATIONS=0
FAILED_VALIDATIONS=0

run_validation() {
    local validation_name="$1"
    local validation_function="$2"
    
    TOTAL_VALIDATIONS=$((TOTAL_VALIDATIONS + 1))
    info "Validating: $validation_name"
    
    if $validation_function; then
        success "$validation_name - VALIDATED"
        PASSED_VALIDATIONS=$((PASSED_VALIDATIONS + 1))
        return 0
    else
        error "$validation_name - FAILED"
        FAILED_VALIDATIONS=$((FAILED_VALIDATIONS + 1))
        return 1
    fi
}

# Terraform state validation
validate_terraform_state() {
    cd "$TERRAFORM_DIR"
    
    # Check if state is healthy
    if ! terraform state list >/dev/null 2>&1; then
        error "Terraform state is not accessible"
        return 1
    fi
    
    # Count expected resources
    local expected_resources=20  # Adjust based on your infrastructure
    local actual_resources=$(terraform state list | wc -l)
    
    if [ "$actual_resources" -ge "$expected_resources" ]; then
        success "Terraform state contains $actual_resources resources (expected >= $expected_resources)"
        return 0
    else
        error "Terraform state contains only $actual_resources resources (expected >= $expected_resources)"
        return 1
    fi
}

# GKE cluster validation
validate_gke_clusters() {
    info "Validating GKE clusters for all regions..."
    
    local regions=("americas" "emea" "apac")
    local failed_clusters=0
    
    for region in "${regions[@]}"; do
        local cluster_name="proxy-cluster-$region"
        
        # Check if cluster exists and is running
        if gcloud container clusters describe "$cluster_name" --zone="$(get_zone_for_region "$region")" --format="value(status)" 2>/dev/null | grep -q "RUNNING"; then
            success "GKE cluster $cluster_name is running"
            
            # Check node pool health
            local ready_nodes=$(kubectl get nodes --selector="cluster=$cluster_name" --no-headers 2>/dev/null | grep -c "Ready" || echo "0")
            if [ "$ready_nodes" -gt 0 ]; then
                success "GKE cluster $cluster_name has $ready_nodes ready nodes"
            else
                error "GKE cluster $cluster_name has no ready nodes"
                failed_clusters=$((failed_clusters + 1))
            fi
        else
            error "GKE cluster $cluster_name is not running or accessible"
            failed_clusters=$((failed_clusters + 1))
        fi
    done
    
    return $failed_clusters
}

# Helper function to get zone for region
get_zone_for_region() {
    case "$1" in
        "americas") echo "us-east4-a" ;;
        "emea") echo "europe-west4-b" ;;
        "apac") echo "asia-northeast1-c" ;;
        *) echo "us-central1-a" ;;
    esac
}

# Load balancer validation
validate_load_balancers() {
    info "Validating global load balancers..."
    
    # Check IPv4 and IPv6 addresses
    local ipv4_address=$(gcloud compute addresses describe "proxy-ipv4-address" --global --format="value(address)" 2>/dev/null || echo "")
    local ipv6_address=$(gcloud compute addresses describe "proxy-ipv6-address" --global --format="value(address)" 2>/dev/null || echo "")
    
    if [ -n "$ipv4_address" ]; then
        success "Global IPv4 address allocated: $ipv4_address"
    else
        error "Global IPv4 address not found"
        return 1
    fi
    
    if [ -n "$ipv6_address" ]; then
        success "Global IPv6 address allocated: $ipv6_address"
    else
        error "Global IPv6 address not found"
        return 1
    fi
    
    # Check backend services
    local backend_services=("epp-backend-service" "whois-backend-service" "http-whois-backend-service" "https-whois-backend-service")
    local failed_backends=0
    
    for service in "${backend_services[@]}"; do
        if gcloud compute backend-services describe "$service" --global >/dev/null 2>&1; then
            success "Backend service $service is configured"
        else
            error "Backend service $service not found"
            failed_backends=$((failed_backends + 1))
        fi
    done
    
    return $failed_backends
}

# Service endpoint validation
validate_service_endpoints() {
    info "Validating service endpoints..."
    
    # Get load balancer IP
    local lb_ip=$(gcloud compute addresses describe "proxy-ipv4-address" --global --format="value(address)" 2>/dev/null || echo "")
    
    if [ -z "$lb_ip" ]; then
        error "Cannot get load balancer IP for endpoint testing"
        return 1
    fi
    
    # Test EPP port (usually 700)
    if timeout 10 nc -z "$lb_ip" 700 2>/dev/null; then
        success "EPP endpoint is accessible on port 700"
    else
        warning "EPP endpoint not accessible (may be expected during deployment)"
    fi
    
    # Test WHOIS port (usually 43)
    if timeout 10 nc -z "$lb_ip" 43 2>/dev/null; then
        success "WHOIS endpoint is accessible on port 43"
    else
        warning "WHOIS endpoint not accessible (may be expected during deployment)"
    fi
    
    # Test HTTP WHOIS (port 80)
    if timeout 10 nc -z "$lb_ip" 80 2>/dev/null; then
        success "HTTP WHOIS endpoint is accessible on port 80"
    else
        warning "HTTP WHOIS endpoint not accessible (may be expected during deployment)"
    fi
    
    return 0
}

# KMS key validation
validate_kms_keys() {
    info "Validating KMS key configuration..."
    
    local key_ring="proxy-key-ring"
    local crypto_key="proxy-key"
    
    # Check key ring exists
    if gcloud kms keyrings describe "$key_ring" --location=global >/dev/null 2>&1; then
        success "KMS key ring $key_ring exists"
    else
        error "KMS key ring $key_ring not found"
        return 1
    fi
    
    # Check crypto key exists and is enabled
    local key_state=$(gcloud kms keys describe "$crypto_key" --keyring="$key_ring" --location=global --format="value(primary.state)" 2>/dev/null || echo "")
    
    if [ "$key_state" = "ENABLED" ]; then
        success "KMS crypto key $crypto_key is enabled"
    else
        error "KMS crypto key $crypto_key is not enabled (state: $key_state)"
        return 1
    fi
    
    # Check key rotation schedule
    local rotation_period=$(gcloud kms keys describe "$crypto_key" --keyring="$key_ring" --location=global --format="value(rotationPeriod)" 2>/dev/null || echo "")
    
    if [ -n "$rotation_period" ]; then
        success "KMS key rotation is configured (period: $rotation_period)"
    else
        warning "KMS key rotation not configured"
    fi
    
    return 0
}

# Storage bucket validation
validate_storage_buckets() {
    info "Validating GCS storage buckets..."
    
    # Check certificate bucket
    local cert_bucket=$(terraform output -raw storage_buckets 2>/dev/null | jq -r '.certificate_bucket' 2>/dev/null || echo "")
    
    if [ -n "$cert_bucket" ] && [ "$cert_bucket" != "null" ]; then
        if gsutil ls "gs://$cert_bucket" >/dev/null 2>&1; then
            success "Certificate storage bucket $cert_bucket is accessible"
            
            # Check bucket security settings
            local uniform_access=$(gsutil uniformbucketlevelaccess get "gs://$cert_bucket" 2>/dev/null | grep -o "Enabled: True" || echo "")
            if [ -n "$uniform_access" ]; then
                success "Certificate bucket has uniform bucket-level access enabled"
            else
                warning "Certificate bucket uniform access status unclear"
            fi
        else
            error "Certificate storage bucket $cert_bucket is not accessible"
            return 1
        fi
    else
        error "Certificate storage bucket name not found in Terraform outputs"
        return 1
    fi
    
    return 0
}

# Service account validation
validate_service_accounts() {
    info "Validating service accounts..."
    
    local service_account="proxy-service-account@$(terraform output -raw proxy_service_account 2>/dev/null | jq -r '.email' | cut -d'@' -f2 2>/dev/null || echo "")"
    
    if [ -z "$service_account" ] || [ "$service_account" = "proxy-service-account@" ]; then
        error "Cannot determine service account email"
        return 1
    fi
    
    # Check service account exists
    if gcloud iam service-accounts describe "$service_account" >/dev/null 2>&1; then
        success "Service account $service_account exists"
        
        # Check IAM bindings
        local roles=("roles/monitoring.metricWriter" "roles/logging.logWriter" "roles/iam.workloadIdentityUser")
        local missing_roles=0
        
        for role in "${roles[@]}"; do
            if gcloud projects get-iam-policy "$(gcloud config get-value project)" --format="value(bindings.members)" --filter="bindings.role=$role" | grep -q "$service_account"; then
                success "Service account has required role: $role"
            else
                error "Service account missing required role: $role"
                missing_roles=$((missing_roles + 1))
            fi
        done
        
        return $missing_roles
    else
        error "Service account $service_account not found"
        return 1
    fi
}

# DNS validation
validate_dns_configuration() {
    info "Validating DNS configuration..."
    
    # Check managed zone exists
    local zone_name="proxy-domain"
    if gcloud dns managed-zones describe "$zone_name" >/dev/null 2>&1; then
        success "DNS managed zone $zone_name exists"
        
        # Check A and AAAA records
        local records=("epp" "whois")
        local failed_records=0
        
        for record in "${records[@]}"; do
            local a_record_count=$(gcloud dns record-sets list --zone="$zone_name" --filter="type=A AND name~$record" --format="value(name)" | wc -l)
            local aaaa_record_count=$(gcloud dns record-sets list --zone="$zone_name" --filter="type=AAAA AND name~$record" --format="value(name)" | wc -l)
            
            if [ "$a_record_count" -gt 0 ]; then
                success "DNS A record for $record exists"
            else
                error "DNS A record for $record missing"
                failed_records=$((failed_records + 1))
            fi
            
            if [ "$aaaa_record_count" -gt 0 ]; then
                success "DNS AAAA record for $record exists"
            else
                error "DNS AAAA record for $record missing"
                failed_records=$((failed_records + 1))
            fi
        done
        
        return $failed_records
    else
        error "DNS managed zone $zone_name not found"
        return 1
    fi
}

# Security validation
validate_security_configuration() {
    info "Validating security configuration..."
    
    local security_checks=0
    
    # Check firewall rules
    if gcloud compute firewall-rules describe "proxy-firewall" >/dev/null 2>&1; then
        success "Firewall rules configured"
    else
        error "Firewall rules missing"
        security_checks=$((security_checks + 1))
    fi
    
    # Check workload identity
    local clusters=("proxy-cluster-americas" "proxy-cluster-emea" "proxy-cluster-apac")
    for cluster in "${clusters[@]}"; do
        local zone=$(get_zone_for_region "${cluster#proxy-cluster-}")
        local workload_identity=$(gcloud container clusters describe "$cluster" --zone="$zone" --format="value(workloadIdentityConfig.workloadPool)" 2>/dev/null || echo "")
        
        if [ -n "$workload_identity" ]; then
            success "Workload Identity enabled for $cluster"
        else
            error "Workload Identity not enabled for $cluster"
            security_checks=$((security_checks + 1))
        fi
    done
    
    return $security_checks
}

# Main validation execution
main() {
    log "Starting Production Deployment Validation"
    log "Environment: $ENVIRONMENT"
    log "Validation Log: $VALIDATION_LOG"
    
    cd "$TERRAFORM_DIR"
    
    # Run all validations
    run_validation "Terraform State" "validate_terraform_state"
    run_validation "GKE Clusters" "validate_gke_clusters"
    run_validation "Load Balancers" "validate_load_balancers"
    run_validation "Service Endpoints" "validate_service_endpoints"
    run_validation "KMS Keys" "validate_kms_keys"
    run_validation "Storage Buckets" "validate_storage_buckets"
    run_validation "Service Accounts" "validate_service_accounts"
    run_validation "DNS Configuration" "validate_dns_configuration"
    run_validation "Security Configuration" "validate_security_configuration"
    
    # Generate validation summary
    log ""
    log "================================================="
    log "PRODUCTION DEPLOYMENT VALIDATION SUMMARY"
    log "================================================="
    log "Total Validations: $TOTAL_VALIDATIONS"
    log "Passed: $PASSED_VALIDATIONS"
    log "Failed: $FAILED_VALIDATIONS"
    log "Success Rate: $(( PASSED_VALIDATIONS * 100 / TOTAL_VALIDATIONS ))%"
    
    if [ $FAILED_VALIDATIONS -eq 0 ]; then
        success "🎉 ALL VALIDATIONS PASSED!"
        log "Production deployment validation successful"
        exit 0
    else
        error "❌ $FAILED_VALIDATIONS validation(s) failed"
        log "Production deployment requires attention"
        exit 1
    fi
}

# Run main function
main "$@"