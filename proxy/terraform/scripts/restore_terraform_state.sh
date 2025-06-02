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

# Terraform State Restoration Script
# Restores Terraform state from backup with validation

set -euo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TERRAFORM_DIR="$(dirname "$SCRIPT_DIR")"
BACKUP_DIR="${TERRAFORM_DIR}/backups"
LOG_FILE="${BACKUP_DIR}/restore.log"

# Default values
BACKUP_BUCKET="${BACKUP_BUCKET:-nomulus-terraform-backups}"
STATE_BUCKET="${STATE_BUCKET:-YOUR_GCS_BUCKET}"
STATE_PREFIX="${STATE_PREFIX:-terraform/state}"
DRY_RUN="${DRY_RUN:-false}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging function
log() {
    local level=$1
    shift
    local message="$*"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    
    case $level in
        ERROR)
            echo -e "${RED}[ERROR]${NC} $message" >&2
            echo "[$timestamp] [ERROR] $message" >> "$LOG_FILE"
            ;;
        WARN)
            echo -e "${YELLOW}[WARN]${NC} $message" >&2
            echo "[$timestamp] [WARN] $message" >> "$LOG_FILE"
            ;;
        INFO)
            echo -e "${GREEN}[INFO]${NC} $message"
            echo "[$timestamp] [INFO] $message" >> "$LOG_FILE"
            ;;
        DEBUG)
            echo -e "${BLUE}[DEBUG]${NC} $message"
            echo "[$timestamp] [DEBUG] $message" >> "$LOG_FILE"
            ;;
    esac
}

# Check dependencies
check_dependencies() {
    log INFO "Checking dependencies..."
    
    if ! command -v gsutil &> /dev/null; then
        log ERROR "gsutil not found. Please install Google Cloud SDK."
        exit 1
    fi
    
    if ! command -v terraform &> /dev/null; then
        log ERROR "terraform not found. Please install Terraform."
        exit 1
    fi
    
    if ! command -v jq &> /dev/null; then
        log ERROR "jq not found. Please install jq for JSON processing."
        exit 1
    fi
    
    log INFO "Dependencies check passed."
}

# List available backups
list_backups() {
    log INFO "Available backups:"
    echo
    
    # List local backups
    if [ -d "$BACKUP_DIR" ]; then
        echo "Local backups:"
        for backup in $(ls -1 "$BACKUP_DIR" | grep -E '^20[0-9]{6}_[0-9]{6}$' | sort -r | head -10); do
            if [ -f "${BACKUP_DIR}/${backup}/terraform.tfstate" ]; then
                local size=$(du -h "${BACKUP_DIR}/${backup}/terraform.tfstate" | cut -f1)
                echo "  $backup (${size})"
            fi
        done
        echo
    fi
    
    # List remote backups
    echo "Remote backups (last 10):"
    gsutil ls "gs://${BACKUP_BUCKET}/backups/" | grep -E '/20[0-9]{6}_[0-9]{6}/$' | sort -r | head -10 | while read backup_path; do
        local backup_name=$(basename "$backup_path")
        echo "  $backup_name"
    done
}

# Validate backup integrity
validate_backup() {
    local backup_date=$1
    local backup_source=$2
    
    log INFO "Validating backup integrity for $backup_date..."
    
    local temp_file="/tmp/terraform_state_${backup_date}.tfstate"
    local metadata_file="/tmp/metadata_${backup_date}.json"
    
    # Download backup and metadata
    if [ "$backup_source" = "local" ]; then
        cp "${BACKUP_DIR}/${backup_date}/terraform.tfstate" "$temp_file"
        cp "${BACKUP_DIR}/${backup_date}/metadata.json" "$metadata_file" 2>/dev/null || true
    else
        gsutil cp "gs://${BACKUP_BUCKET}/backups/${backup_date}/terraform.tfstate" "$temp_file"
        gsutil cp "gs://${BACKUP_BUCKET}/backups/${backup_date}/metadata.json" "$metadata_file" 2>/dev/null || true
    fi
    
    # Validate file exists and is not empty
    if [ ! -s "$temp_file" ]; then
        log ERROR "Backup file is empty or doesn't exist"
        return 1
    fi
    
    # Validate JSON format
    if ! jq empty "$temp_file" 2>/dev/null; then
        log ERROR "Backup file is not valid JSON"
        return 1
    fi
    
    # Check if it looks like a Terraform state file
    if ! jq -e '.version' "$temp_file" >/dev/null 2>&1; then
        log ERROR "File doesn't appear to be a Terraform state file"
        return 1
    fi
    
    # Validate checksum if metadata exists
    if [ -f "$metadata_file" ]; then
        local expected_checksum=$(jq -r '.checksum' "$metadata_file")
        local actual_checksum=$(sha256sum "$temp_file" | cut -d' ' -f1)
        
        if [ "$expected_checksum" != "$actual_checksum" ]; then
            log ERROR "Checksum mismatch!"
            log ERROR "Expected: $expected_checksum"
            log ERROR "Actual:   $actual_checksum"
            return 1
        else
            log INFO "Checksum validation passed: $actual_checksum"
        fi
    else
        log WARN "No metadata file found, skipping checksum validation"
    fi
    
    # Cleanup temp files
    rm -f "$temp_file" "$metadata_file"
    
    log INFO "Backup validation completed successfully"
    return 0
}

# Create current state backup before restoration
backup_current_state() {
    log INFO "Creating backup of current state before restoration..."
    
    local current_backup_date=$(date +%Y%m%d_%H%M%S)_pre_restore
    local current_backup_dir="${BACKUP_DIR}/${current_backup_date}"
    
    mkdir -p "$current_backup_dir"
    
    # Download current state
    if gsutil -q stat "gs://${STATE_BUCKET}/${STATE_PREFIX}/default.tfstate"; then
        gsutil cp "gs://${STATE_BUCKET}/${STATE_PREFIX}/default.tfstate" "${current_backup_dir}/terraform.tfstate"
        
        # Create metadata
        cat > "${current_backup_dir}/metadata.json" << EOF
{
  "backup_date": "$current_backup_date",
  "backup_type": "pre_restore",
  "original_state_bucket": "$STATE_BUCKET",
  "original_state_prefix": "$STATE_PREFIX",
  "created_by": "${USER:-unknown}",
  "hostname": "$(hostname)"
}
EOF
        
        log INFO "Current state backed up to $current_backup_dir"
        echo "$current_backup_date" > "${BACKUP_DIR}/.last_pre_restore_backup"
    else
        log WARN "No current state file found to backup"
    fi
}

# Restore state from backup
restore_state() {
    local backup_date=$1
    local backup_source=$2
    
    log INFO "Restoring Terraform state from backup: $backup_date"
    
    if [ "$DRY_RUN" = "true" ]; then
        log INFO "DRY RUN: Would restore state from $backup_date ($backup_source)"
        return 0
    fi
    
    # Create backup of current state
    backup_current_state
    
    local temp_file="/tmp/terraform_state_restore_${backup_date}.tfstate"
    
    # Download backup
    if [ "$backup_source" = "local" ]; then
        cp "${BACKUP_DIR}/${backup_date}/terraform.tfstate" "$temp_file"
    else
        gsutil cp "gs://${BACKUP_BUCKET}/backups/${backup_date}/terraform.tfstate" "$temp_file"
    fi
    
    # Upload to state bucket
    log INFO "Uploading restored state to gs://${STATE_BUCKET}/${STATE_PREFIX}/default.tfstate"
    gsutil cp "$temp_file" "gs://${STATE_BUCKET}/${STATE_PREFIX}/default.tfstate"
    
    # Cleanup temp file
    rm -f "$temp_file"
    
    log INFO "State restoration completed"
}

# Validate restored state
validate_restored_state() {
    log INFO "Validating restored state..."
    
    # Change to terraform directory for validation
    cd "$TERRAFORM_DIR"
    
    # Check if terraform can read the state
    log INFO "Running terraform plan to validate state..."
    if terraform plan -detailed-exitcode -no-color > /tmp/terraform_plan_output.txt 2>&1; then
        log INFO "Terraform plan completed successfully - no changes needed"
    elif [ $? -eq 2 ]; then
        log WARN "Terraform plan shows changes are needed - this may be expected"
        log INFO "Plan output saved to /tmp/terraform_plan_output.txt"
    else
        log ERROR "Terraform plan failed - state may be corrupted"
        log ERROR "Plan output:"
        cat /tmp/terraform_plan_output.txt
        return 1
    fi
    
    # Show state statistics
    log INFO "State validation completed. State statistics:"
    terraform show -json | jq -r '.values.root_module.resources | length' | xargs -I {} echo "  Resources: {}"
}

# Main execution
main() {
    local backup_date=${1:-}
    local backup_source="remote"
    
    # Setup logging
    mkdir -p "$BACKUP_DIR"
    touch "$LOG_FILE"
    
    log INFO "Starting Terraform state restoration process..."
    
    check_dependencies
    
    if [ -z "$backup_date" ]; then
        echo "Usage: $0 <backup_date> [local|remote]"
        echo
        list_backups
        exit 1
    fi
    
    # Check if backup source is specified
    if [ "${2:-}" = "local" ]; then
        backup_source="local"
        if [ ! -f "${BACKUP_DIR}/${backup_date}/terraform.tfstate" ]; then
            log ERROR "Local backup not found: ${BACKUP_DIR}/${backup_date}/terraform.tfstate"
            exit 1
        fi
    else
        # Check if remote backup exists
        if ! gsutil -q stat "gs://${BACKUP_BUCKET}/backups/${backup_date}/terraform.tfstate"; then
            log ERROR "Remote backup not found: gs://${BACKUP_BUCKET}/backups/${backup_date}/terraform.tfstate"
            exit 1
        fi
    fi
    
    # Validate backup
    if ! validate_backup "$backup_date" "$backup_source"; then
        log ERROR "Backup validation failed. Aborting restoration."
        exit 1
    fi
    
    # Confirm restoration
    if [ "$DRY_RUN" != "true" ]; then
        echo
        echo -e "${YELLOW}WARNING: This will replace the current Terraform state!${NC}"
        echo "Backup date: $backup_date"
        echo "Backup source: $backup_source"
        echo
        read -p "Are you sure you want to proceed? (yes/no): " confirm
        
        if [ "$confirm" != "yes" ]; then
            log INFO "Restoration cancelled by user"
            exit 0
        fi
    fi
    
    # Perform restoration
    restore_state "$backup_date" "$backup_source"
    
    # Validate restored state
    validate_restored_state
    
    log INFO "State restoration completed successfully!"
    
    if [ -f "${BACKUP_DIR}/.last_pre_restore_backup" ]; then
        local pre_restore_backup=$(cat "${BACKUP_DIR}/.last_pre_restore_backup")
        echo
        echo "Restoration Summary:"
        echo "==================="
        echo "Restored from: $backup_date ($backup_source)"
        echo "Pre-restore backup: $pre_restore_backup"
        echo "To rollback this restoration, run:"
        echo "  $0 $pre_restore_backup local"
    fi
}

# Show usage
usage() {
    cat << EOF
Usage: $0 <backup_date> [local|remote] [OPTIONS]

Restore Terraform state from backup with validation.

Arguments:
  backup_date       Backup timestamp (YYYYMMDD_HHMMSS format)
  source           'local' or 'remote' (default: remote)

Environment Variables:
  BACKUP_BUCKET     GCS bucket for backups (default: nomulus-terraform-backups)
  STATE_BUCKET      GCS bucket with current state (default: YOUR_GCS_BUCKET)
  STATE_PREFIX      State file prefix (default: terraform/state)
  DRY_RUN          Set to 'true' for dry run (default: false)

Examples:
  $0 20250602_143000                    # Restore from remote backup
  $0 20250602_143000 local              # Restore from local backup
  DRY_RUN=true $0 20250602_143000       # Dry run restoration
  $0 list                               # List available backups

EOF
}

# Handle command line arguments
case "${1:-}" in
    -h|--help)
        usage
        exit 0
        ;;
    list)
        check_dependencies
        list_backups
        exit 0
        ;;
    *)
        main "$@"
        ;;
esac