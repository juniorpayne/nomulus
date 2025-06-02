# Terraform State Emergency Recovery Guide

**⚠️ EMERGENCY USE ONLY ⚠️**

This guide provides step-by-step procedures for recovering from Terraform state corruption, loss, or provider upgrade failures.

## Quick Recovery Commands

### 1. Immediate Assessment
```bash
# Check current state status
cd /path/to/nomulus/proxy/terraform
terraform plan -detailed-exitcode

# If terraform fails to read state:
# Proceed to Emergency Recovery procedures below
```

### 2. Emergency State Restoration
```bash
# List available backups
./scripts/restore_terraform_state.sh list

# Restore from most recent backup (replace DATE with actual backup date)
./scripts/restore_terraform_state.sh YYYYMMDD_HHMMSS

# Validate restored state
terraform plan -detailed-exitcode
```

## Emergency Scenarios

### Scenario 1: State File Corruption
**Symptoms**: Terraform cannot read state file, shows parsing errors

**Recovery Steps**:
1. **Stop all Terraform operations immediately**
2. **Identify the issue**:
   ```bash
   terraform show  # Will show error details
   ```
3. **Restore from backup**:
   ```bash
   ./scripts/restore_terraform_state.sh list
   ./scripts/restore_terraform_state.sh YYYYMMDD_HHMMSS
   ```
4. **Validate recovery**:
   ```bash
   terraform plan -no-color > recovery_validation.txt
   ```
5. **Notify stakeholders** of issue and recovery status

### Scenario 2: Provider Upgrade Failure
**Symptoms**: Provider upgrade causes state incompatibility, resource errors

**Recovery Steps**:
1. **Document the error**:
   ```bash
   terraform plan -no-color > provider_upgrade_error.txt 2>&1
   ```
2. **Restore pre-upgrade state**:
   ```bash
   # Look for pre-upgrade backup (created automatically)
   ls -la backups/ | grep pre_restore
   ./scripts/restore_terraform_state.sh YYYYMMDD_HHMMSS_pre_restore local
   ```
3. **Downgrade provider version** (if applicable):
   ```bash
   # Edit terraform configuration to use previous provider version
   # Run terraform init -upgrade=false
   ```
4. **Validate rollback**:
   ```bash
   terraform plan -detailed-exitcode
   ```

### Scenario 3: State File Loss
**Symptoms**: State file missing from GCS bucket, terraform init shows no state

**Recovery Steps**:
1. **Confirm state loss**:
   ```bash
   gsutil ls gs://YOUR_STATE_BUCKET/terraform/state/
   ```
2. **Restore from most recent backup**:
   ```bash
   ./scripts/restore_terraform_state.sh list
   ./scripts/restore_terraform_state.sh YYYYMMDD_HHMMSS
   ```
3. **If no backups available** - **CRITICAL SITUATION**:
   ```bash
   # Import existing resources (this is complex and time-consuming)
   # Contact senior DevOps engineer immediately
   # Document all known resources for manual import
   ```

### Scenario 4: Multiple Backup Failures
**Symptoms**: Both local and remote backups are corrupted or missing

**Recovery Steps**:
1. **EMERGENCY ESCALATION** - Contact:
   - DevOps Team Lead
   - CTO
   - Infrastructure Team
2. **Document current infrastructure**:
   ```bash
   # List all GCP resources that were managed by Terraform
   gcloud compute instances list
   gcloud container clusters list
   gcloud dns managed-zones list
   # etc.
   ```
3. **Prepare for manual resource import**:
   ```bash
   # This will require extensive manual work
   # Each resource will need to be imported individually
   terraform import google_compute_instance.example projects/PROJECT/zones/ZONE/instances/INSTANCE
   ```

## Recovery Validation Checklist

After any recovery procedure, complete this checklist:

### ✅ State File Validation
- [ ] `terraform plan` runs without errors
- [ ] Plan shows expected infrastructure (no unexpected changes)
- [ ] State file size is reasonable (not empty, not corrupted)
- [ ] All expected resources are present in state

### ✅ Infrastructure Validation
- [ ] All GKE clusters are running and accessible
- [ ] Load balancers are functioning correctly
- [ ] DNS resolution is working
- [ ] All services are responding to health checks
- [ ] No unexpected resource drift detected

### ✅ Functional Validation
- [ ] EPP service accessible on port 700
- [ ] WHOIS service accessible on port 43
- [ ] Web WHOIS accessible on ports 80/443
- [ ] All regional deployments operational
- [ ] Monitoring and alerting functional

### ✅ Security Validation
- [ ] IAM permissions intact
- [ ] KMS keys accessible
- [ ] Service accounts functioning
- [ ] Network security rules enforced
- [ ] No unauthorized access detected

## Post-Recovery Actions

### Immediate (0-1 hour)
1. **Create fresh backup** of recovered state
2. **Notify stakeholders** of recovery completion
3. **Document incident** with timeline and actions taken
4. **Monitor infrastructure** for any anomalies

### Short-term (1-24 hours)
1. **Perform comprehensive testing** of all services
2. **Review and update backup procedures** based on incident
3. **Conduct post-incident review** with team
4. **Update monitoring** to detect similar issues

### Long-term (1-7 days)
1. **Implement additional safeguards** identified during incident
2. **Update disaster recovery procedures**
3. **Conduct team training** on lessons learned
4. **Review and improve backup retention policies**

## Emergency Contacts

### Primary Escalation
- **DevOps Team Lead**: [Contact Info]
- **Senior Infrastructure Engineer**: [Contact Info]
- **On-call Engineer**: [Contact Info]

### Secondary Escalation
- **CTO**: [Contact Info]
- **Engineering Manager**: [Contact Info]
- **Platform Team Lead**: [Contact Info]

### External Support
- **Google Cloud Support**: [Support Case URL]
- **HashiCorp Support**: [Support Case URL]

## Recovery Time Objectives (RTO)

| Scenario | Target RTO | Maximum RTO |
|----------|------------|-------------|
| State Corruption | 30 minutes | 1 hour |
| Provider Failure | 1 hour | 2 hours |
| State Loss | 2 hours | 4 hours |
| Multiple Backup Failure | 4 hours | 8 hours |

## Prevention Measures

### Automated Safeguards
- Daily state backups with integrity verification
- Pre-change state snapshots
- Cross-region backup replication
- Automated backup validation

### Process Safeguards
- Mandatory staging environment testing
- Peer review for all infrastructure changes
- Change approval process for production
- Regular disaster recovery drills

### Monitoring
- State file integrity monitoring
- Backup success/failure alerting
- Infrastructure drift detection
- Service health monitoring

## Important Notes

⚠️ **Always create a backup before making any changes**
⚠️ **Test recovery procedures regularly in staging**
⚠️ **Keep this document updated with current contact information**
⚠️ **Never modify state files manually**
⚠️ **Always validate recovery before declaring incident resolved**

---

**Document Version**: 1.0  
**Last Updated**: June 2, 2025  
**Next Review**: July 2, 2025