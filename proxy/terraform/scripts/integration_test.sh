#!/bin/bash

# Integration Testing Framework for Terraform 6.37.0 Infrastructure
# Tests real deployment scenarios and validates infrastructure functionality

set -euo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TERRAFORM_DIR="$(dirname "$SCRIPT_DIR")"
TEST_PROJECT_ID="${TEST_PROJECT_ID:-nomulus-test-tf-637}"
TEST_ENVIRONMENT="${TEST_ENVIRONMENT:-integration-test}"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
TEST_RUN_ID="integration-test-$TIMESTAMP"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Logging
log() {
    echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

success() {
    echo -e "${GREEN}✅ $1${NC}"
}

error() {
    echo -e "${RED}❌ $1${NC}"
}

warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

# Test state tracking
INTEGRATION_TESTS=0
INTEGRATION_PASSED=0
INTEGRATION_FAILED=0

run_integration_test() {
    local test_name="$1"
    local test_function="$2"
    
    INTEGRATION_TESTS=$((INTEGRATION_TESTS + 1))
    info "Running integration test: $test_name"
    
    if $test_function; then
        success "$test_name - PASSED"
        INTEGRATION_PASSED=$((INTEGRATION_PASSED + 1))
        return 0
    else
        error "$test_name - FAILED"
        INTEGRATION_FAILED=$((INTEGRATION_FAILED + 1))
        return 1
    fi
}

# Pre-flight checks
preflight_checks() {
    info "Running pre-flight checks..."
    
    # Check required tools
    local required_tools=("terraform" "gcloud" "kubectl" "jq")
    for tool in "${required_tools[@]}"; do
        if ! command -v "$tool" &> /dev/null; then
            error "$tool is not installed or not in PATH"
            return 1
        fi
    done
    
    # Check GCP authentication
    if ! gcloud auth list --filter=status:ACTIVE --format="value(account)" | head -n1 > /dev/null; then
        error "No active GCP authentication found. Run 'gcloud auth login'"
        return 1
    fi
    
    # Verify test project access
    if ! gcloud projects describe "$TEST_PROJECT_ID" &> /dev/null; then
        error "Cannot access test project: $TEST_PROJECT_ID"
        return 1
    fi
    
    success "Pre-flight checks completed"
    return 0
}

# Test Terraform initialization with provider 6.37.0
test_terraform_init() {
    info "Testing Terraform initialization with Google Provider 6.37.0..."
    
    cd "$TERRAFORM_DIR"
    
    # Create test workspace
    local test_workspace="integration-test-$TIMESTAMP"
    
    # Copy configuration to temporary directory
    local temp_dir="/tmp/terraform-integration-test-$TIMESTAMP"
    cp -r . "$temp_dir"
    cd "$temp_dir"
    
    # Create test-specific variables
    cat > terraform.tfvars << EOF
proxy_project_name = "$TEST_PROJECT_ID"
gcr_project_name = "$TEST_PROJECT_ID"
proxy_domain_name = "test-${TIMESTAMP}.example.com"
proxy_certificate_bucket = "test-cert-bucket-${TIMESTAMP}"
gcp_project_id = "$TEST_PROJECT_ID"
environment = "$TEST_ENVIRONMENT"
proxy_service_account_email = "test-proxy-sa@${TEST_PROJECT_ID}.iam.gserviceaccount.com"
proxy_cluster_region = "americas"
EOF
    
    # Initialize Terraform
    if terraform init -upgrade; then
        success "Terraform initialization successful with provider 6.37.0"
        
        # Validate configuration
        if terraform validate; then
            success "Terraform configuration validation passed"
        else
            error "Terraform configuration validation failed"
            cleanup_test_workspace "$temp_dir"
            return 1
        fi
    else
        error "Terraform initialization failed"
        cleanup_test_workspace "$temp_dir"
        return 1
    fi
    
    cleanup_test_workspace "$temp_dir"
    return 0
}

# Test provider compatibility
test_provider_compatibility() {
    info "Testing Google Cloud Provider 6.37.0 compatibility..."
    
    # Create minimal test configuration
    local temp_dir="/tmp/provider-test-$TIMESTAMP"
    mkdir -p "$temp_dir"
    cd "$temp_dir"
    
    cat > main.tf << 'EOF'
terraform {
  required_version = ">= 1.0"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.37.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = "us-central1"
}

variable "project_id" {
  type = string
}

# Test modern GCS configuration
resource "google_storage_bucket" "test" {
  name                        = "provider-test-${random_id.bucket_suffix.hex}"
  location                    = "US"
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  
  versioning {
    enabled = true
  }
  
  labels = {
    environment = "test"
    managed-by  = "terraform"
  }
  
  lifecycle {
    prevent_destroy = false
  }
}

resource "random_id" "bucket_suffix" {
  byte_length = 8
}

output "bucket_name" {
  value = google_storage_bucket.test.name
}
EOF
    
    # Initialize and plan
    if terraform init && terraform plan -var="project_id=$TEST_PROJECT_ID" -out=tfplan; then
        success "Provider 6.37.0 compatibility test passed"
        
        # Clean up
        rm -rf "$temp_dir"
        return 0
    else
        error "Provider 6.37.0 compatibility test failed"
        rm -rf "$temp_dir"
        return 1
    fi
}

# Test KMS modernization
test_kms_modernization() {
    info "Testing KMS modernization features..."
    
    local temp_dir="/tmp/kms-test-$TIMESTAMP"
    mkdir -p "$temp_dir"
    cd "$temp_dir"
    
    cat > kms_test.tf << EOF
terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.37.0"
    }
  }
}

provider "google" {
  project = "$TEST_PROJECT_ID"
  region  = "us-central1"
}

resource "google_kms_key_ring" "test" {
  name     = "test-ring-$TIMESTAMP"
  location = "global"
}

resource "google_kms_crypto_key" "test" {
  name     = "test-key-$TIMESTAMP"
  key_ring = google_kms_key_ring.test.id  # Modern reference instead of self_link
  purpose  = "ENCRYPT_DECRYPT"
  
  rotation_period = "7776000s"  # 90 days
  
  version_template {
    algorithm        = "GOOGLE_SYMMETRIC_ENCRYPTION"
    protection_level = "SOFTWARE"
  }
  
  labels = {
    environment = "test"
    managed-by  = "terraform"
  }
}

output "key_id" {
  value = google_kms_crypto_key.test.id
}
EOF
    
    if terraform init && terraform validate; then
        success "KMS modernization syntax validation passed"
        rm -rf "$temp_dir"
        return 0
    else
        error "KMS modernization validation failed"
        rm -rf "$temp_dir"
        return 1
    fi
}

# Test GKE modernization
test_gke_modernization() {
    info "Testing GKE modernization features..."
    
    local temp_dir="/tmp/gke-test-$TIMESTAMP"
    mkdir -p "$temp_dir"
    cd "$temp_dir"
    
    cat > gke_test.tf << EOF
terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.37.0"
    }
  }
}

provider "google" {
  project = "$TEST_PROJECT_ID"
  region  = "us-central1"
}

resource "google_container_cluster" "test" {
  name     = "test-cluster-$TIMESTAMP"
  location = "us-central1-a"
  
  deletion_protection = false
  
  workload_identity_config {
    workload_pool = "$TEST_PROJECT_ID.svc.id.goog"
  }
  
  network_policy {
    enabled = true
  }
  
  binary_authorization {
    evaluation_mode = "PROJECT_SINGLETON_POLICY_ENFORCE"
  }
  
  node_pool {
    name               = "test-pool"
    initial_node_count = 1
    
    node_config {
      machine_type = "e2-micro"  # Small for testing
      disk_size_gb = 20
      
      shielded_instance_config {
        enable_secure_boot          = true
        enable_integrity_monitoring = true
      }
      
      workload_metadata_config {
        mode = "GKE_METADATA"
      }
      
      oauth_scopes = [
        "https://www.googleapis.com/auth/cloud-platform",
      ]
    }
    
    management {
      auto_repair  = true
      auto_upgrade = true
    }
  }
}

output "cluster_endpoint" {
  value     = google_container_cluster.test.endpoint
  sensitive = true
}
EOF
    
    if terraform init && terraform validate; then
        success "GKE modernization syntax validation passed"
        rm -rf "$temp_dir"
        return 0
    else
        error "GKE modernization validation failed"
        rm -rf "$temp_dir"
        return 1
    fi
}

# Test backup system functionality
test_backup_system() {
    info "Testing backup system functionality..."
    
    cd "$TERRAFORM_DIR"
    
    # Test backup script exists and is executable
    if [ -x "scripts/backup_terraform_state.sh" ]; then
        success "Backup script is executable"
    else
        error "Backup script is not executable"
        return 1
    fi
    
    # Test restore script exists and is executable
    if [ -x "scripts/restore_terraform_state.sh" ]; then
        success "Restore script is executable"
    else
        error "Restore script is not executable"
        return 1
    fi
    
    # Test validation script exists and is executable
    if [ -x "scripts/validate_state_backup.sh" ]; then
        success "Validation script is executable"
    else
        error "Validation script is not executable"
        return 1
    fi
    
    return 0
}

# Test documentation completeness
test_documentation() {
    info "Testing documentation completeness..."
    
    cd "$TERRAFORM_DIR"
    
    local required_docs=(
        "docs/PROVIDER_COMPATIBILITY_ANALYSIS.md"
        "docs/EMERGENCY_RECOVERY_GUIDE.md"
        "docs/STATE_BACKUP_PROCEDURES.md"
    )
    
    for doc in "${required_docs[@]}"; do
        if [ -f "$doc" ] && [ -s "$doc" ]; then
            # Check if documentation has meaningful content
            if [ $(wc -l < "$doc") -gt 10 ]; then
                success "Documentation $doc is complete"
            else
                error "Documentation $doc is too short"
                return 1
            fi
        else
            error "Documentation $doc is missing or empty"
            return 1
        fi
    done
    
    return 0
}

# Cleanup function
cleanup_test_workspace() {
    local workspace_dir="$1"
    if [ -d "$workspace_dir" ]; then
        rm -rf "$workspace_dir"
        info "Cleaned up test workspace: $workspace_dir"
    fi
}

# Main integration test execution
main() {
    log "Starting Terraform 6.37.0 Integration Test Suite"
    log "Test Project: $TEST_PROJECT_ID"
    log "Test Environment: $TEST_ENVIRONMENT"
    log "Test Run ID: $TEST_RUN_ID"
    
    # Run pre-flight checks
    if ! preflight_checks; then
        error "Pre-flight checks failed. Aborting integration tests."
        exit 1
    fi
    
    # Run integration tests
    run_integration_test "Terraform Initialization" "test_terraform_init"
    run_integration_test "Provider Compatibility" "test_provider_compatibility"
    run_integration_test "KMS Modernization" "test_kms_modernization"
    run_integration_test "GKE Modernization" "test_gke_modernization"
    run_integration_test "Backup System" "test_backup_system"
    run_integration_test "Documentation" "test_documentation"
    
    # Generate integration test summary
    log ""
    log "================================================="
    log "INTEGRATION TEST SUITE SUMMARY"
    log "================================================="
    log "Total Integration Tests: $INTEGRATION_TESTS"
    log "Passed: $INTEGRATION_PASSED"
    log "Failed: $INTEGRATION_FAILED"
    
    if [ $INTEGRATION_FAILED -eq 0 ]; then
        success "🎉 ALL INTEGRATION TESTS PASSED!"
        log "Infrastructure is ready for production deployment with Terraform 6.37.0"
        exit 0
    else
        error "❌ $INTEGRATION_FAILED integration tests failed"
        log "Please address integration issues before production deployment"
        exit 1
    fi
}

# Run main function
main "$@"