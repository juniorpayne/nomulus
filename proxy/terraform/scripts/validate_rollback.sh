#!/bin/bash

# Rollback Validation Script
# Validates successful rollback of Terraform infrastructure to previous state

set -euo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TERRAFORM_DIR="$(dirname "$SCRIPT_DIR")"
ROLLBACK_LOG="$TERRAFORM_DIR/test-results/rollback_validation_$(date +%Y%m%d_%H%M%S).log"
BACKUP_TIMESTAMP="${1:-}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Logging
log() {
    echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$ROLLBACK_LOG"
}

success() {
    echo -e "${GREEN}✅ $1${NC}" | tee -a "$ROLLBACK_LOG"
}

error() {
    echo -e "${RED}❌ $1${NC}" | tee -a "$ROLLBACK_LOG"
}

warning() {
    echo -e "${YELLOW}⚠️  $1${NC}" | tee -a "$ROLLBACK_LOG"
}

info() {
    echo -e "${BLUE}ℹ️  $1${NC}" | tee -a "$ROLLBACK_LOG"
}

# Validation counters
TOTAL_CHECKS=0
PASSED_CHECKS=0
FAILED_CHECKS=0

run_check() {
    local check_name="$1"
    local check_function="$2"
    
    TOTAL_CHECKS=$((TOTAL_CHECKS + 1))
    info "Checking: $check_name"
    
    if $check_function; then
        success "$check_name - OK"
        PASSED_CHECKS=$((PASSED_CHECKS + 1))
        return 0
    else
        error "$check_name - FAILED"
        FAILED_CHECKS=$((FAILED_CHECKS + 1))
        return 1
    fi
}

# Check if services are responding
check_service_availability() {
    info "Checking service availability after rollback..."
    
    # Get load balancer IP
    local lb_ip=""
    if command -v terraform >/dev/null 2>&1; then
        lb_ip=$(terraform output -raw proxy_ip_addresses 2>/dev/null | jq -r '.ipv4' 2>/dev/null || echo "")
    fi
    
    if [ -z "$lb_ip" ] || [ "$lb_ip" = "null" ]; then
        # Try to get IP from GCP directly
        lb_ip=$(gcloud compute addresses describe "proxy-ipv4-address" --global --format="value(address)" 2>/dev/null || echo "")
    fi
    
    if [ -z "$lb_ip" ]; then
        error "Cannot determine load balancer IP"
        return 1
    fi
    
    info "Testing connectivity to load balancer IP: $lb_ip"
    
    # Test basic connectivity
    if ping -c 3 -W 5 "$lb_ip" >/dev/null 2>&1; then
        success "Load balancer IP is reachable"
    else
        warning "Load balancer IP ping failed (may be expected)"
    fi
    
    # Test service ports
    local ports=("700:EPP" "43:WHOIS" "80:HTTP-WHOIS")
    local failed_ports=0
    
    for port_info in "${ports[@]}"; do
        local port="${port_info%:*}"
        local service="${port_info#*:}"
        
        if timeout 10 nc -z "$lb_ip" "$port" 2>/dev/null; then
            success "$service service (port $port) is accessible"
        else
            warning "$service service (port $port) not accessible (may be starting up)"
            failed_ports=$((failed_ports + 1))
        fi
    done
    
    # If more than half the ports are failing, consider it a failure
    if [ $failed_ports -gt 1 ]; then
        error "Multiple services are not accessible after rollback"
        return 1
    fi
    
    return 0
}

# Check Terraform state consistency
check_terraform_state() {
    cd "$TERRAFORM_DIR"
    
    # Verify state is accessible
    if ! terraform state list >/dev/null 2>&1; then
        error "Terraform state is not accessible"
        return 1
    fi
    
    # Check for state corruption
    if terraform validate >/dev/null 2>&1; then
        success "Terraform configuration is valid"
    else
        error "Terraform configuration validation failed"
        return 1
    fi
    
    # Verify resource count is reasonable
    local resource_count=$(terraform state list | wc -l)
    if [ "$resource_count" -gt 10 ]; then  # Adjust threshold as needed
        success "Terraform state contains $resource_count resources"
    else
        error "Terraform state contains only $resource_count resources (may be incomplete)"
        return 1
    fi
    
    return 0
}

# Check GKE cluster health
check_gke_cluster_health() {
    info "Checking GKE cluster health..."
    
    local regions=("americas" "emea" "apac")
    local unhealthy_clusters=0
    
    for region in "${regions[@]}"; do
        local cluster_name="proxy-cluster-$region"
        local zone=$(get_zone_for_region "$region")
        
        # Check cluster status
        local cluster_status=$(gcloud container clusters describe "$cluster_name" --zone="$zone" --format="value(status)" 2>/dev/null || echo "UNKNOWN")
        
        if [ "$cluster_status" = "RUNNING" ]; then
            success "GKE cluster $cluster_name is running"
            
            # Check node health
            local ready_nodes=$(kubectl get nodes --selector="cluster=$cluster_name" --no-headers 2>/dev/null | grep -c "Ready" || echo "0")
            local total_nodes=$(kubectl get nodes --selector="cluster=$cluster_name" --no-headers 2>/dev/null | wc -l || echo "0")
            
            if [ "$ready_nodes" -eq "$total_nodes" ] && [ "$ready_nodes" -gt 0 ]; then
                success "All $ready_nodes nodes in cluster $cluster_name are ready"
            else
                warning "Cluster $cluster_name has $ready_nodes/$total_nodes nodes ready"
                if [ "$ready_nodes" -eq 0 ]; then
                    unhealthy_clusters=$((unhealthy_clusters + 1))
                fi
            fi
        else
            error "GKE cluster $cluster_name status: $cluster_status"
            unhealthy_clusters=$((unhealthy_clusters + 1))
        fi
    done
    
    if [ $unhealthy_clusters -eq 0 ]; then
        return 0
    else
        error "$unhealthy_clusters cluster(s) are not healthy"
        return 1
    fi
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

# Check load balancer configuration
check_load_balancer_config() {
    info "Checking load balancer configuration..."
    
    # Check global addresses
    local ipv4_status=$(gcloud compute addresses describe "proxy-ipv4-address" --global --format="value(status)" 2>/dev/null || echo "NOT_FOUND")
    local ipv6_status=$(gcloud compute addresses describe "proxy-ipv6-address" --global --format="value(status)" 2>/dev/null || echo "NOT_FOUND")
    
    if [ "$ipv4_status" = "RESERVED" ]; then
        success "Global IPv4 address is properly reserved"
    else
        error "Global IPv4 address status: $ipv4_status"
        return 1
    fi
    
    if [ "$ipv6_status" = "RESERVED" ]; then
        success "Global IPv6 address is properly reserved"
    else
        error "Global IPv6 address status: $ipv6_status"
        return 1
    fi
    
    # Check backend services
    local backend_services=("epp-backend-service" "whois-backend-service" "http-whois-backend-service")
    local failed_backends=0
    
    for service in "${backend_services[@]}"; do
        if gcloud compute backend-services describe "$service" --global >/dev/null 2>&1; then
            # Check backend health
            local healthy_backends=$(gcloud compute backend-services get-health "$service" --global --format="value(status.healthStatus.healthState)" 2>/dev/null | grep -c "HEALTHY" || echo "0")
            
            if [ "$healthy_backends" -gt 0 ]; then
                success "Backend service $service has $healthy_backends healthy backends"
            else
                warning "Backend service $service has no healthy backends (may be starting up)"
            fi
        else
            error "Backend service $service not found"
            failed_backends=$((failed_backends + 1))
        fi
    done
    
    return $failed_backends
}

# Check DNS resolution
check_dns_resolution() {
    info "Checking DNS resolution..."
    
    # Get domain name from Terraform output or configuration
    local domain_name=""
    if command -v terraform >/dev/null 2>&1; then
        domain_name=$(terraform output -raw proxy_domain_name 2>/dev/null || echo "")
    fi
    
    if [ -z "$domain_name" ] || [ "$domain_name" = "null" ]; then
        warning "Cannot determine domain name, skipping DNS resolution check"
        return 0
    fi
    
    # Remove trailing dot if present
    domain_name="${domain_name%.}"
    
    # Test EPP and WHOIS DNS resolution
    local dns_services=("epp" "whois")
    local failed_dns=0
    
    for service in "${dns_services[@]}"; do
        local fqdn="${service}.${domain_name}"
        
        if nslookup "$fqdn" >/dev/null 2>&1; then
            success "DNS resolution for $fqdn works"
        else
            error "DNS resolution for $fqdn failed"
            failed_dns=$((failed_dns + 1))
        fi
    done
    
    return $failed_dns
}

# Check monitoring and logging
check_monitoring_logging() {
    info "Checking monitoring and logging configuration..."
    
    # Check if monitoring dashboards exist
    local dashboard_count=$(gcloud monitoring dashboards list --format="value(name)" 2>/dev/null | wc -l || echo "0")
    
    if [ "$dashboard_count" -gt 0 ]; then
        success "Monitoring dashboards are configured ($dashboard_count found)"
    else
        warning "No monitoring dashboards found"
    fi
    
    # Check logging sinks
    local sink_count=$(gcloud logging sinks list --format="value(name)" 2>/dev/null | wc -l || echo "0")
    
    if [ "$sink_count" -gt 0 ]; then
        success "Logging sinks are configured ($sink_count found)"
    else
        warning "No logging sinks found"
    fi
    
    return 0
}

# Check backup integrity
check_backup_integrity() {
    if [ -n "$BACKUP_TIMESTAMP" ]; then
        info "Validating backup used for rollback: $BACKUP_TIMESTAMP"
        
        local backup_dir="$TERRAFORM_DIR/backups"
        local backup_file="$backup_dir/terraform-state-backup-$BACKUP_TIMESTAMP.tar.gz"
        
        if [ -f "$backup_file" ]; then
            success "Backup file exists: $backup_file"
            
            # Verify backup integrity
            if tar -tzf "$backup_file" >/dev/null 2>&1; then
                success "Backup file integrity verified"
            else
                error "Backup file is corrupted"
                return 1
            fi
        else
            warning "Backup file not found: $backup_file"
        fi
    else
        warning "No backup timestamp provided, skipping backup integrity check"
    fi
    
    return 0
}

# Check security configuration
check_security_configuration() {
    info "Checking security configuration..."
    
    # Check service account
    local sa_email=$(terraform output -raw proxy_service_account 2>/dev/null | jq -r '.email' 2>/dev/null || echo "")
    
    if [ -n "$sa_email" ] && [ "$sa_email" != "null" ]; then
        if gcloud iam service-accounts describe "$sa_email" >/dev/null 2>&1; then
            success "Service account $sa_email exists"
        else
            error "Service account $sa_email not found"
            return 1
        fi
    else
        warning "Cannot determine service account email"
    fi
    
    # Check KMS keys
    if gcloud kms keys list --location=global --keyring=proxy-key-ring >/dev/null 2>&1; then
        success "KMS keys are accessible"
    else
        error "KMS keys are not accessible"
        return 1
    fi
    
    return 0
}

# Main rollback validation
main() {
    log "Starting Rollback Validation"
    if [ -n "$BACKUP_TIMESTAMP" ]; then
        log "Backup Timestamp: $BACKUP_TIMESTAMP"
    fi
    log "Validation Log: $ROLLBACK_LOG"
    
    # Run all checks
    run_check "Service Availability" "check_service_availability"
    run_check "Terraform State" "check_terraform_state"
    run_check "GKE Cluster Health" "check_gke_cluster_health"
    run_check "Load Balancer Configuration" "check_load_balancer_config"
    run_check "DNS Resolution" "check_dns_resolution"
    run_check "Monitoring and Logging" "check_monitoring_logging"
    run_check "Backup Integrity" "check_backup_integrity"
    run_check "Security Configuration" "check_security_configuration"
    
    # Generate rollback validation summary
    log ""
    log "================================================="
    log "ROLLBACK VALIDATION SUMMARY"
    log "================================================="
    log "Total Checks: $TOTAL_CHECKS"
    log "Passed: $PASSED_CHECKS"
    log "Failed: $FAILED_CHECKS"
    log "Success Rate: $(( PASSED_CHECKS * 100 / TOTAL_CHECKS ))%"
    
    if [ $FAILED_CHECKS -eq 0 ]; then
        success "🎉 ROLLBACK VALIDATION SUCCESSFUL!"
        log "Infrastructure rollback completed successfully"
        log "All systems are operational"
        exit 0
    elif [ $FAILED_CHECKS -le 2 ]; then
        warning "⚠️  ROLLBACK PARTIALLY SUCCESSFUL"
        log "$FAILED_CHECKS minor issues detected, but core functionality appears intact"
        log "Manual investigation recommended"
        exit 1
    else
        error "❌ ROLLBACK VALIDATION FAILED"
        log "$FAILED_CHECKS critical issues detected"
        log "Immediate action required"
        exit 2
    fi
}

# Display usage if no arguments provided
if [ $# -eq 0 ]; then
    echo "Usage: $0 [backup-timestamp]"
    echo ""
    echo "Validates the success of a Terraform infrastructure rollback."
    echo ""
    echo "Arguments:"
    echo "  backup-timestamp    Optional timestamp of the backup used for rollback"
    echo ""
    echo "Examples:"
    echo "  $0                           # Validate rollback without backup verification"
    echo "  $0 20250603_140530          # Validate rollback with backup verification"
    echo ""
fi

# Run main function
main "$@"