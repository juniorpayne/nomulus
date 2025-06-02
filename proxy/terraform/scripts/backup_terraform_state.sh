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

# Terraform State Backup Script
# Creates timestamped backups of Terraform state with integrity verification

set -euo pipefail

# Configuration
DATE=$(date +%Y%m%d_%H%M%S)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TERRAFORM_DIR="$(dirname "$SCRIPT_DIR")"
BACKUP_DIR="${TERRAFORM_DIR}/backups"
LOG_FILE="${BACKUP_DIR}/backup.log"

# Default values - can be overridden by environment variables
BACKUP_BUCKET="${BACKUP_BUCKET:-nomulus-terraform-backups}"
STATE_BUCKET="${STATE_BUCKET:-YOUR_GCS_BUCKET}"
STATE_PREFIX="${STATE_PREFIX:-terraform/state}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
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
    
    log INFO "Dependencies check passed."
}

# Create backup directory structure
setup_backup_structure() {
    log INFO "Setting up backup directory structure..."
    
    mkdir -p "${BACKUP_DIR}/${DATE}"
    mkdir -p "${BACKUP_DIR}/logs"
    
    # Create log file if it doesn't exist
    touch "$LOG_FILE"
    
    log INFO "Backup structure created at ${BACKUP_DIR}/${DATE}"
}

# Backup Terraform state
backup_state() {
    log INFO "Starting Terraform state backup..."
    
    local local_backup="${BACKUP_DIR}/${DATE}/terraform.tfstate"
    local remote_backup="gs://${BACKUP_BUCKET}/backups/${DATE}/terraform.tfstate"
    local state_source="gs://${STATE_BUCKET}/${STATE_PREFIX}/default.tfstate"
    
    # Check if state file exists
    if ! gsutil -q stat "$state_source"; then
        log ERROR "State file not found: $state_source"
        exit 1
    fi
    
    # Download state to local backup
    log INFO "Downloading state file to local backup..."
    if gsutil cp "$state_source" "$local_backup"; then
        log INFO "State file downloaded to $local_backup"
    else
        log ERROR "Failed to download state file"
        exit 1
    fi
    
    # Upload to backup bucket
    log INFO "Uploading backup to remote storage..."
    if gsutil cp "$local_backup" "$remote_backup"; then
        log INFO "Backup uploaded to $remote_backup"
    else
        log ERROR "Failed to upload backup to remote storage"
        exit 1
    fi
    
    return 0
}

# Verify backup integrity
verify_backup() {
    log INFO "Verifying backup integrity..."
    
    local local_backup="${BACKUP_DIR}/${DATE}/terraform.tfstate"
    local remote_backup="gs://${BACKUP_BUCKET}/backups/${DATE}/terraform.tfstate"
    
    # Get checksums
    local local_hash=$(sha256sum "$local_backup" | cut -d' ' -f1)
    local remote_hash=$(gsutil hash -h "$remote_backup" | grep "Hash (sha256)" | cut -d: -f2 | tr -d ' ')
    
    # Convert remote hash to lowercase for comparison
    remote_hash=$(echo "$remote_hash" | tr '[:upper:]' '[:lower:]')
    
    if [ "$local_hash" = "$remote_hash" ]; then
        log INFO "Backup integrity verified: $local_hash"
        echo "$local_hash" > "${BACKUP_DIR}/${DATE}/checksum.sha256"
    else
        log ERROR "Backup integrity check failed!"
        log ERROR "Local:  $local_hash"
        log ERROR "Remote: $remote_hash"
        exit 1
    fi
}

# Create backup metadata
create_metadata() {
    log INFO "Creating backup metadata..."
    
    local metadata_file="${BACKUP_DIR}/${DATE}/metadata.json"
    
    cat > "$metadata_file" << EOF
{
  "backup_date": "$DATE",
  "terraform_version": "$(terraform version -json | jq -r '.terraform_version')",
  "state_bucket": "$STATE_BUCKET",
  "state_prefix": "$STATE_PREFIX",
  "backup_bucket": "$BACKUP_BUCKET",
  "backup_path": "backups/${DATE}/terraform.tfstate",
  "checksum": "$(cat "${BACKUP_DIR}/${DATE}/checksum.sha256")",
  "created_by": "${USER:-unknown}",
  "hostname": "$(hostname)"
}
EOF
    
    # Upload metadata to backup bucket
    gsutil cp "$metadata_file" "gs://${BACKUP_BUCKET}/backups/${DATE}/metadata.json"
    
    log INFO "Metadata created and uploaded"
}

# Clean up old backups
cleanup_old_backups() {
    log INFO "Cleaning up backups older than $RETENTION_DAYS days..."
    
    # Clean up local backups
    find "$BACKUP_DIR" -name "20*" -type d -mtime +$RETENTION_DAYS -exec rm -rf {} + 2>/dev/null || true
    
    # Clean up remote backups (this is more complex with gsutil, so we'll just log it)
    log INFO "To clean up remote backups older than $RETENTION_DAYS days, run:"
    log INFO "gsutil -m rm -r gs://${BACKUP_BUCKET}/backups/\$(date -d '$RETENTION_DAYS days ago' +%Y%m%d)*"
    
    log INFO "Local cleanup completed"
}

# Main execution
main() {
    log INFO "Starting Terraform state backup process..."
    log INFO "Backup timestamp: $DATE"
    
    check_dependencies
    setup_backup_structure
    backup_state
    verify_backup
    create_metadata
    cleanup_old_backups
    
    log INFO "Backup completed successfully!"
    log INFO "Local backup: ${BACKUP_DIR}/${DATE}/"
    log INFO "Remote backup: gs://${BACKUP_BUCKET}/backups/${DATE}/"
    
    echo
    echo "Backup Summary:"
    echo "==============="
    echo "Date: $DATE"
    echo "Local: ${BACKUP_DIR}/${DATE}/terraform.tfstate"
    echo "Remote: gs://${BACKUP_BUCKET}/backups/${DATE}/terraform.tfstate"
    echo "Checksum: $(cat "${BACKUP_DIR}/${DATE}/checksum.sha256")"
}

# Show usage
usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Backup Terraform state with integrity verification.

Environment Variables:
  BACKUP_BUCKET     GCS bucket for backups (default: nomulus-terraform-backups)
  STATE_BUCKET      GCS bucket with current state (default: YOUR_GCS_BUCKET)
  STATE_PREFIX      State file prefix (default: terraform/state)
  RETENTION_DAYS    Local backup retention (default: 30)

Examples:
  $0                                    # Use default settings
  STATE_BUCKET=my-state-bucket $0       # Custom state bucket
  RETENTION_DAYS=7 $0                   # Keep backups for 7 days

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