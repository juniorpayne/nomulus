#!/bin/bash
# Copyright 2025 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Terraform State Backup Validation Script
# Validates backup integrity and completeness

set -euo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TERRAFORM_DIR="$(dirname "$SCRIPT_DIR")"
BACKUP_DIR="${TERRAFORM_DIR}/backups"

# Default values
BACKUP_BUCKET="${BACKUP_BUCKET:-nomulus-terraform-backups}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Validation results
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# Test result function
test_result() {
    local test_name=$1
    local result=$2
    local message=${3:-}
    
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    
    if [ "$result" = "PASS" ]; then
        echo -e "${GREEN}✓${NC} $test_name"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo -e "${RED}✗${NC} $test_name"
        [ -n "$message" ] && echo "    $message"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

# Check dependencies
check_dependencies() {
    echo "Checking dependencies..."
    
    if command -v gsutil &> /dev/null; then
        test_result "gsutil available" "PASS"
    else
        test_result "gsutil available" "FAIL" "Google Cloud SDK not installed"
    fi
    
    if command -v terraform &> /dev/null; then
        test_result "terraform available" "PASS"
    else
        test_result "terraform available" "FAIL" "Terraform not installed"
    fi
    
    if command -v jq &> /dev/null; then
        test_result "jq available" "PASS"
    else
        test_result "jq available" "FAIL" "jq not installed"
    fi
}

# Validate backup scripts
validate_backup_scripts() {
    echo
    echo "Validating backup scripts..."
    
    local backup_script="${SCRIPT_DIR}/backup_terraform_state.sh"
    local restore_script="${SCRIPT_DIR}/restore_terraform_state.sh"
    
    if [ -f "$backup_script" ] && [ -x "$backup_script" ]; then
        test_result "Backup script exists and executable" "PASS"
    else
        test_result "Backup script exists and executable" "FAIL"
    fi
    
    if [ -f "$restore_script" ] && [ -x "$restore_script" ]; then
        test_result "Restore script exists and executable" "PASS"
    else
        test_result "Restore script exists and executable" "FAIL"
    fi
    
    # Check script syntax
    if bash -n "$backup_script" 2>/dev/null; then
        test_result "Backup script syntax valid" "PASS"
    else
        test_result "Backup script syntax valid" "FAIL"
    fi
    
    if bash -n "$restore_script" 2>/dev/null; then
        test_result "Restore script syntax valid" "PASS"
    else
        test_result "Restore script syntax valid" "FAIL"
    fi
}

# Validate backup directory structure
validate_backup_structure() {
    echo
    echo "Validating backup structure..."
    
    if [ -d "$BACKUP_DIR" ]; then
        test_result "Backup directory exists" "PASS"
    else
        test_result "Backup directory exists" "FAIL" "$BACKUP_DIR not found"
        return
    fi
    
    # Check for existing backups
    local backup_count=$(find "$BACKUP_DIR" -name "20*" -type d 2>/dev/null | wc -l)
    if [ "$backup_count" -gt 0 ]; then
        test_result "Local backups exist ($backup_count found)" "PASS"
        
        # Validate structure of most recent backup
        local latest_backup=$(ls -1 "$BACKUP_DIR" | grep -E '^20[0-9]{6}_[0-9]{6}$' | sort -r | head -1)
        if [ -n "$latest_backup" ]; then
            local backup_path="${BACKUP_DIR}/${latest_backup}"
            
            if [ -f "${backup_path}/terraform.tfstate" ]; then
                test_result "Latest backup has state file" "PASS"
            else
                test_result "Latest backup has state file" "FAIL"
            fi
            
            if [ -f "${backup_path}/metadata.json" ]; then
                test_result "Latest backup has metadata" "PASS"
                
                # Validate metadata JSON
                if jq empty "${backup_path}/metadata.json" 2>/dev/null; then
                    test_result "Metadata is valid JSON" "PASS"
                else
                    test_result "Metadata is valid JSON" "FAIL"
                fi
            else
                test_result "Latest backup has metadata" "FAIL"
            fi
            
            if [ -f "${backup_path}/checksum.sha256" ]; then
                test_result "Latest backup has checksum" "PASS"
            else
                test_result "Latest backup has checksum" "FAIL"
            fi
        fi
    else
        test_result "Local backups exist" "FAIL" "No local backups found"
    fi
}

# Validate remote backup access
validate_remote_access() {
    echo
    echo "Validating remote backup access..."
    
    # Test GCS access
    if gsutil ls "gs://${BACKUP_BUCKET}/" &> /dev/null; then
        test_result "Can access backup bucket" "PASS"
    else
        test_result "Can access backup bucket" "FAIL" "Cannot access gs://${BACKUP_BUCKET}/"
        return
    fi
    
    # Check for remote backups
    local remote_backups=$(gsutil ls "gs://${BACKUP_BUCKET}/backups/" 2>/dev/null | grep -E '/20[0-9]{6}_[0-9]{6}/$' | wc -l)
    if [ "$remote_backups" -gt 0 ]; then
        test_result "Remote backups exist ($remote_backups found)" "PASS"
    else
        test_result "Remote backups exist" "FAIL" "No remote backups found"
    fi
}

# Test backup and restore workflow
test_backup_workflow() {
    echo
    echo "Testing backup workflow (dry run)..."
    
    # Test backup script help
    if "${SCRIPT_DIR}/backup_terraform_state.sh" --help &> /dev/null; then
        test_result "Backup script help works" "PASS"
    else
        test_result "Backup script help works" "FAIL"
    fi
    
    # Test restore script help
    if "${SCRIPT_DIR}/restore_terraform_state.sh" --help &> /dev/null; then
        test_result "Restore script help works" "PASS"
    else
        test_result "Restore script help works" "FAIL"
    fi
    
    # Test restore script list function
    if "${SCRIPT_DIR}/restore_terraform_state.sh" list &> /dev/null; then
        test_result "Restore script list function works" "PASS"
    else
        test_result "Restore script list function works" "FAIL"
    fi
}

# Validate backup integrity
validate_backup_integrity() {
    echo
    echo "Validating backup integrity..."
    
    if [ ! -d "$BACKUP_DIR" ]; then
        test_result "Backup integrity check" "FAIL" "No backup directory found"
        return
    fi
    
    local backup_count=0
    local valid_backups=0
    
    for backup_dir in $(find "$BACKUP_DIR" -name "20*" -type d 2>/dev/null | sort -r | head -5); do
        backup_count=$((backup_count + 1))
        local backup_name=$(basename "$backup_dir")
        
        if [ -f "${backup_dir}/terraform.tfstate" ] && [ -f "${backup_dir}/checksum.sha256" ]; then
            local stored_checksum=$(cat "${backup_dir}/checksum.sha256")
            local actual_checksum=$(sha256sum "${backup_dir}/terraform.tfstate" | cut -d' ' -f1)
            
            if [ "$stored_checksum" = "$actual_checksum" ]; then
                valid_backups=$((valid_backups + 1))
            fi
        fi
    done
    
    if [ "$backup_count" -eq 0 ]; then
        test_result "Backup integrity validation" "FAIL" "No backups to validate"
    elif [ "$valid_backups" -eq "$backup_count" ]; then
        test_result "Backup integrity validation ($valid_backups/$backup_count)" "PASS"
    else
        test_result "Backup integrity validation ($valid_backups/$backup_count)" "FAIL" "Some backups failed integrity check"
    fi
}

# Main execution
main() {
    echo "Terraform State Backup Validation"
    echo "================================="
    echo
    
    check_dependencies
    validate_backup_scripts
    validate_backup_structure
    validate_remote_access
    test_backup_workflow
    validate_backup_integrity
    
    echo
    echo "Validation Summary"
    echo "=================="
    echo "Total tests: $TOTAL_TESTS"
    echo -e "Passed: ${GREEN}$PASSED_TESTS${NC}"
    echo -e "Failed: ${RED}$FAILED_TESTS${NC}"
    
    if [ "$FAILED_TESTS" -eq 0 ]; then
        echo -e "\n${GREEN}✓ All validation tests passed!${NC}"
        echo "Backup and recovery procedures are ready for use."
        exit 0
    else
        echo -e "\n${RED}✗ Some validation tests failed.${NC}"
        echo "Please address the issues before relying on backup procedures."
        exit 1
    fi
}

# Show usage
usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Validate Terraform state backup and recovery procedures.

Environment Variables:
  BACKUP_BUCKET     GCS bucket for backups (default: nomulus-terraform-backups)

This script performs comprehensive validation of:
- Dependencies (gsutil, terraform, jq)
- Backup and restore scripts
- Backup directory structure
- Remote backup access
- Backup workflow functionality
- Backup integrity

EOF
}

# Handle command line arguments
case "${1:-}" in
    -h|--help)
        usage
        exit 0
        ;;
    *)
        main "$@"
        ;;
esac