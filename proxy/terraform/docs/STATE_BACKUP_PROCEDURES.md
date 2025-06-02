# Terraform State Backup Procedures

This document outlines the standard procedures for backing up and managing Terraform state files to ensure infrastructure recovery capability.

## Overview

The backup system provides automated, verified backups of Terraform state files with the following features:
- Automated daily backups with integrity verification
- Local and remote backup storage
- Point-in-time recovery capability
- Pre-change snapshot creation
- Comprehensive validation and testing

## Backup Architecture

### Storage Locations
- **Local Backups**: `./backups/YYYYMMDD_HHMMSS/`
- **Remote Backups**: `gs://nomulus-terraform-backups/backups/YYYYMMDD_HHMMSS/`
- **State Location**: `gs://YOUR_GCS_BUCKET/terraform/state/`

### Backup Components
Each backup includes:
- `terraform.tfstate` - The actual state file
- `metadata.json` - Backup metadata and context
- `checksum.sha256` - Integrity verification checksum

## Standard Procedures

### Daily Backup Creation

**Automated Process** (recommended):
```bash
# Set up cron job for daily backups
0 2 * * * cd /path/to/nomulus/proxy/terraform && ./scripts/backup_terraform_state.sh

# Or run manually
./scripts/backup_terraform_state.sh
```

**Manual Process**:
```bash
# Navigate to terraform directory
cd /path/to/nomulus/proxy/terraform

# Create backup with default settings
./scripts/backup_terraform_state.sh

# Create backup with custom settings
STATE_BUCKET=my-state-bucket BACKUP_BUCKET=my-backup-bucket ./scripts/backup_terraform_state.sh
```

### Pre-Change Backups

**Before any infrastructure changes**:
```bash
# Create snapshot before changes
./scripts/backup_terraform_state.sh

# Document the backup timestamp
echo "Pre-change backup: $(date +%Y%m%d_%H%M%S)" >> CHANGELOG.md
```

### Backup Validation

**Daily validation** (automated):
```bash
# Run comprehensive validation
./scripts/validate_state_backup.sh

# Check specific backup integrity
./scripts/restore_terraform_state.sh YYYYMMDD_HHMMSS --dry-run
```

### Backup Restoration

**Standard restoration process**:
```bash
# List available backups
./scripts/restore_terraform_state.sh list

# Restore from specific backup (with confirmation)
./scripts/restore_terraform_state.sh YYYYMMDD_HHMMSS

# Restore from local backup
./scripts/restore_terraform_state.sh YYYYMMDD_HHMMSS local

# Dry run restoration (test only)
DRY_RUN=true ./scripts/restore_terraform_state.sh YYYYMMDD_HHMMSS
```

## Configuration

### Environment Variables

```bash
# Required configurations
export STATE_BUCKET="your-terraform-state-bucket"
export BACKUP_BUCKET="nomulus-terraform-backups"

# Optional configurations
export STATE_PREFIX="terraform/state"        # Default: terraform/state
export RETENTION_DAYS="30"                   # Default: 30 days
export DRY_RUN="false"                      # Default: false
```

### GCS Bucket Setup

**Create backup bucket**:
```bash
# Create bucket with appropriate permissions
gsutil mb gs://nomulus-terraform-backups

# Set lifecycle policy for automatic cleanup
gsutil lifecycle set backup-lifecycle.json gs://nomulus-terraform-backups

# Set appropriate IAM permissions
gsutil iam ch serviceAccount:terraform-backup@project.iam.gserviceaccount.com:objectCreator gs://nomulus-terraform-backups
```

**Lifecycle policy** (`backup-lifecycle.json`):
```json
{
  "lifecycle": {
    "rule": [
      {
        "action": {"type": "Delete"},
        "condition": {
          "age": 90,
          "matchesPrefix": ["backups/"]
        }
      }
    ]
  }
}
```

## Backup Schedule

### Frequency
- **Daily**: Automated backup at 2 AM UTC
- **Pre-change**: Manual backup before any changes
- **Weekly**: Full validation of all backups
- **Monthly**: Disaster recovery drill

### Retention Policy
- **Local backups**: 30 days (configurable)
- **Remote backups**: 90 days (GCS lifecycle policy)
- **Critical backups**: Indefinite (tagged and preserved)

## Monitoring and Alerting

### Success Monitoring
```bash
# Check backup success
grep "Backup completed successfully" backups/backup.log

# Monitor backup size trends
ls -la backups/*/terraform.tfstate | awk '{print $5, $9}'
```

### Failure Alerting
Set up monitoring for:
- Backup script execution failures
- Integrity check failures
- GCS upload failures
- Disk space issues for local backups

### Health Checks
```bash
# Daily health check script
#!/bin/bash
cd /path/to/nomulus/proxy/terraform

# Check if backup exists for today
TODAY=$(date +%Y%m%d)
if ls backups/${TODAY}_* 1> /dev/null 2>&1; then
    echo "✓ Today's backup exists"
else
    echo "✗ No backup found for today" >&2
    exit 1
fi

# Validate latest backup
LATEST=$(ls -1 backups/ | grep -E '^20[0-9]{6}_[0-9]{6}$' | sort -r | head -1)
if ./scripts/validate_state_backup.sh &> /dev/null; then
    echo "✓ Latest backup validation passed"
else
    echo "✗ Latest backup validation failed" >&2
    exit 1
fi
```

## Troubleshooting

### Common Issues

**Backup script fails with permission error**:
```bash
# Check GCS permissions
gsutil iam get gs://your-backup-bucket

# Test authentication
gcloud auth application-default print-access-token
```

**Backup integrity check fails**:
```bash
# Manual integrity check
sha256sum backups/YYYYMMDD_HHMMSS/terraform.tfstate
cat backups/YYYYMMDD_HHMMSS/checksum.sha256

# Re-download and verify
gsutil cp gs://backup-bucket/backups/YYYYMMDD_HHMMSS/terraform.tfstate /tmp/
sha256sum /tmp/terraform.tfstate
```

**Restoration fails**:
```bash
# Check state file format
jq . backups/YYYYMMDD_HHMMSS/terraform.tfstate

# Validate terraform can read the state
terraform show -json > /dev/null
```

### Emergency Procedures
For critical failures, see [EMERGENCY_RECOVERY_GUIDE.md](./EMERGENCY_RECOVERY_GUIDE.md)

## Best Practices

### Backup Creation
1. **Always backup before changes** - Never skip pre-change backups
2. **Verify backup integrity** - Check checksums after creation
3. **Test restoration periodically** - Monthly restoration tests
4. **Document backup context** - Note what changes prompted the backup

### Backup Management
1. **Monitor backup sizes** - Investigate significant size changes
2. **Cleanup old backups** - Follow retention policies
3. **Secure backup storage** - Ensure proper access controls
4. **Geographic distribution** - Store backups in multiple regions

### Recovery Procedures
1. **Validate before restoring** - Always check backup integrity first
2. **Backup current state** - Create snapshot before restoration
3. **Test after restoration** - Verify terraform plan works correctly
4. **Document the incident** - Keep records of recovery actions

## Security Considerations

### Access Control
- Backup buckets should have restricted access
- Use service accounts with minimal required permissions
- Audit backup access regularly
- Encrypt backups at rest and in transit

### Data Protection
- State files may contain sensitive information
- Follow data classification policies
- Implement appropriate retention policies
- Secure backup transmission channels

## Compliance and Audit

### Audit Trail
- All backup operations are logged with timestamps
- Backup metadata includes creator and context information
- Restoration operations require explicit confirmation
- Failed operations are logged and alerted

### Compliance Requirements
- Backup retention meets regulatory requirements
- Data residency requirements are satisfied
- Access controls meet security standards
- Regular validation demonstrates backup viability

---

**Document Version**: 1.0  
**Last Updated**: June 2, 2025  
**Next Review**: July 2, 2025