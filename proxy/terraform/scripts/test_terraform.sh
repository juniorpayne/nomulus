#!/bin/bash

# Terraform Testing Framework for Google Cloud Provider 6.37.0
# Comprehensive validation and testing script for modernized infrastructure

set -euo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TERRAFORM_DIR="$(dirname "$SCRIPT_DIR")"
PROJECT_ROOT="$(dirname "$TERRAFORM_DIR")"
TEST_RESULTS_DIR="$TERRAFORM_DIR/test-results"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
LOG_FILE="$TEST_RESULTS_DIR/test_run_$TIMESTAMP.log"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Create test results directory
mkdir -p "$TEST_RESULTS_DIR"

# Logging function
log() {
    echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

# Status functions
success() {
    echo -e "${GREEN}✅ $1${NC}" | tee -a "$LOG_FILE"
}

error() {
    echo -e "${RED}❌ $1${NC}" | tee -a "$LOG_FILE"
}

warning() {
    echo -e "${YELLOW}⚠️  $1${NC}" | tee -a "$LOG_FILE"
}

info() {
    echo -e "${BLUE}ℹ️  $1${NC}" | tee -a "$LOG_FILE"
}

# Test counters
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# Function to run a test
run_test() {
    local test_name="$1"
    local test_command="$2"
    
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    info "Running test: $test_name"
    
    if eval "$test_command" >> "$LOG_FILE" 2>&1; then
        success "$test_name - PASSED"
        PASSED_TESTS=$((PASSED_TESTS + 1))
        return 0
    else
        error "$test_name - FAILED"
        FAILED_TESTS=$((FAILED_TESTS + 1))
        return 1
    fi
}

# Terraform version check
check_terraform_version() {
    info "Checking Terraform version compatibility..."
    
    if ! command -v terraform &> /dev/null; then
        error "Terraform is not installed"
        return 1
    fi
    
    local tf_version=$(terraform version -json | jq -r '.terraform_version')
    local required_version="1.0"
    
    if [ "$(printf '%s\n' "$required_version" "$tf_version" | sort -V | head -n1)" = "$required_version" ]; then
        success "Terraform version $tf_version meets minimum requirement ($required_version)"
        return 0
    else
        error "Terraform version $tf_version does not meet minimum requirement ($required_version)"
        return 1
    fi
}

# Provider version validation
validate_provider_versions() {
    info "Validating provider version constraints..."
    
    cd "$TERRAFORM_DIR"
    
    # Check that provider versions are properly constrained
    local provider_files=("example_config.tf" "modules/main.tf")
    
    for file in "${provider_files[@]}"; do
        if [ -f "$file" ]; then
            if grep -q "version.*6\.37\.0" "$file"; then
                success "Provider version constraint found in $file"
            else
                error "Provider version constraint missing or incorrect in $file"
                return 1
            fi
        else
            warning "Provider file $file not found"
        fi
    done
    
    return 0
}

# Terraform syntax validation
validate_terraform_syntax() {
    info "Validating Terraform syntax..."
    
    cd "$TERRAFORM_DIR"
    
    # Initialize if needed
    if [ ! -d ".terraform" ]; then
        info "Initializing Terraform for validation..."
        terraform init -backend=false >/dev/null 2>&1
    fi
    
    # Format check
    if terraform fmt -check -recursive .; then
        success "Terraform formatting is correct"
    else
        error "Terraform formatting issues found"
        return 1
    fi
    
    # Validation check
    if terraform validate; then
        success "Terraform syntax validation passed"
    else
        error "Terraform syntax validation failed"
        return 1
    fi
    
    return 0
}

# Security best practices validation
validate_security_practices() {
    info "Validating security best practices..."
    
    cd "$TERRAFORM_DIR"
    
    local security_checks=(
        "uniform_bucket_level_access.*true:GCS uniform bucket-level access enabled"
        "public_access_prevention.*enforced:GCS public access prevention enforced"
        "workload_identity_config:Workload Identity configured"
        "shielded_instance_config:Shielded nodes enabled"
        "binary_authorization:Binary authorization configured"
        "enabled.*=.*true:Network policies enabled"
        "rotation_period:KMS key rotation configured"
        "prevent_destroy.*true:Lifecycle protection enabled"
    )
    
    for check in "${security_checks[@]}"; do
        local pattern="${check%%:*}"
        local description="${check##*:}"
        
        if grep -r "$pattern" modules/ >/dev/null 2>&1; then
            success "$description"
        else
            error "$description - NOT FOUND"
            return 1
        fi
    done
    
    return 0
}

# Resource naming convention validation
validate_naming_conventions() {
    info "Validating resource naming conventions..."
    
    cd "$TERRAFORM_DIR"
    
    # Check for consistent naming patterns
    local naming_issues=0
    
    # Check for resource labels
    if ! grep -r "managed-by.*terraform" modules/ >/dev/null 2>&1; then
        error "Managed-by labels missing"
        naming_issues=$((naming_issues + 1))
    fi
    
    # Check for environment labels
    if ! grep -r "environment.*var\.environment" modules/ >/dev/null 2>&1; then
        error "Environment labels missing"
        naming_issues=$((naming_issues + 1))
    fi
    
    # Check for component labels
    if ! grep -r "component.*proxy" modules/ >/dev/null 2>&1; then
        error "Component labels missing"
        naming_issues=$((naming_issues + 1))
    fi
    
    if [ $naming_issues -eq 0 ]; then
        success "Resource naming conventions validated"
        return 0
    else
        error "$naming_issues naming convention issues found"
        return 1
    fi
}

# Module interdependency validation
validate_module_dependencies() {
    info "Validating module dependencies and outputs..."
    
    cd "$TERRAFORM_DIR"
    
    local dependency_checks=(
        "modules/gke/output.tf:GKE module outputs"
        "modules/networking/output.tf:Networking module outputs"
        "modules/output.tf:Main module outputs"
    )
    
    for check in "${dependency_checks[@]}"; do
        local file="${check%%:*}"
        local description="${check##*:}"
        
        if [ -f "$file" ] && [ -s "$file" ]; then
            success "$description exist"
        else
            error "$description missing or empty"
            return 1
        fi
    done
    
    return 0
}

# Variable validation
validate_variables() {
    info "Validating variable definitions and types..."
    
    cd "$TERRAFORM_DIR"
    
    local required_vars=(
        "gcp_project_id"
        "environment"
        "proxy_service_account_email"
        "proxy_cluster_region"
    )
    
    for var in "${required_vars[@]}"; do
        if grep -r "variable \"$var\"" modules/ >/dev/null 2>&1; then
            success "Required variable $var defined"
        else
            error "Required variable $var missing"
            return 1
        fi
    done
    
    # Check for proper type constraints
    if grep -r "type.*=.*map(string)" modules/ >/dev/null 2>&1; then
        success "Modern map type constraints found"
    else
        error "Modern map type constraints missing"
        return 1
    fi
    
    return 0
}

# Documentation validation
validate_documentation() {
    info "Validating documentation completeness..."
    
    cd "$TERRAFORM_DIR"
    
    local doc_files=(
        "docs/PROVIDER_COMPATIBILITY_ANALYSIS.md"
        "docs/EMERGENCY_RECOVERY_GUIDE.md"
        "docs/STATE_BACKUP_PROCEDURES.md"
    )
    
    for doc in "${doc_files[@]}"; do
        if [ -f "$doc" ] && [ -s "$doc" ]; then
            success "Documentation file $doc exists"
        else
            error "Documentation file $doc missing or empty"
            return 1
        fi
    done
    
    return 0
}

# Backup system validation
validate_backup_system() {
    info "Validating backup and recovery system..."
    
    cd "$TERRAFORM_DIR"
    
    local backup_components=(
        "scripts/backup_terraform_state.sh"
        "scripts/restore_terraform_state.sh"
        "scripts/validate_state_backup.sh"
    )
    
    for component in "${backup_components[@]}"; do
        if [ -f "$component" ] && [ -x "$component" ]; then
            success "Backup component $component exists and is executable"
        else
            error "Backup component $component missing or not executable"
            return 1
        fi
    done
    
    return 0
}

# Plan generation test (dry run)
test_terraform_plan() {
    info "Testing Terraform plan generation..."
    
    # Create a separate temporary directory for the plan test
    local temp_dir="/tmp/terraform-plan-test-$TIMESTAMP"
    mkdir -p "$temp_dir"
    cd "$temp_dir"
    
    # Create a minimal test configuration
    cat > test_config.tf << EOF
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
  project = "test-project"
  region  = "us-central1"
}

# Test resource to validate provider compatibility
resource "google_storage_bucket" "test" {
  name     = "test-bucket-terraform-validation-\${random_id.bucket_suffix.hex}"
  location = "US"
  
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  
  lifecycle {
    prevent_destroy = false  # For test cleanup
  }
}

resource "random_id" "bucket_suffix" {
  byte_length = 8
}
EOF
    
    # Initialize and validate
    if terraform init && terraform validate && terraform plan -out=test.tfplan; then
        success "Terraform plan test passed"
        cd "$TERRAFORM_DIR"
        rm -rf "$temp_dir"
        return 0
    else
        error "Terraform plan test failed"
        cd "$TERRAFORM_DIR"
        rm -rf "$temp_dir"
        return 1
    fi
}

# Main test execution
main() {
    log "Starting Terraform 6.37.0 Compatibility Test Suite"
    log "Test Results Directory: $TEST_RESULTS_DIR"
    log "Log File: $LOG_FILE"
    
    # Run all tests
    run_test "Terraform Version Check" "check_terraform_version"
    run_test "Provider Version Validation" "validate_provider_versions"
    run_test "Terraform Syntax Validation" "validate_terraform_syntax"
    run_test "Security Best Practices" "validate_security_practices"
    run_test "Naming Conventions" "validate_naming_conventions"
    run_test "Module Dependencies" "validate_module_dependencies"
    run_test "Variable Validation" "validate_variables"
    run_test "Documentation Validation" "validate_documentation"
    run_test "Backup System Validation" "validate_backup_system"
    run_test "Terraform Plan Test" "test_terraform_plan"
    
    # Generate test summary
    log ""
    log "==============================================="
    log "TEST SUITE SUMMARY"
    log "==============================================="
    log "Total Tests: $TOTAL_TESTS"
    log "Passed: $PASSED_TESTS"
    log "Failed: $FAILED_TESTS"
    
    if [ $FAILED_TESTS -eq 0 ]; then
        success "🎉 ALL TESTS PASSED! Infrastructure is ready for Terraform 6.37.0"
        log "Infrastructure validation successful - ready for production deployment"
        exit 0
    else
        error "❌ $FAILED_TESTS tests failed. Please address issues before deployment"
        log "Infrastructure validation failed - issues must be resolved"
        exit 1
    fi
}

# Cleanup function
cleanup() {
    log "Cleaning up test artifacts..."
    cd "$TERRAFORM_DIR"
    rm -f test_config.tf
    # Keep .terraform and .terraform.lock.hcl for subsequent runs
}

# Set trap for cleanup
trap cleanup EXIT

# Run main function
main "$@"