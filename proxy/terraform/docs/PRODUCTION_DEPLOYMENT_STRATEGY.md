# Production Deployment Strategy for Terraform 6.37.0

## Executive Summary

This document outlines the comprehensive production deployment strategy for upgrading the Nomulus proxy infrastructure to Terraform 6.37.0 with Google Cloud Provider 6.37.0. The strategy ensures zero-downtime deployment with comprehensive rollback capabilities.

## Pre-Deployment Validation

### 1. Infrastructure Readiness Checklist

- [x] All Terraform compatibility tests passed (10/10)
- [x] Security best practices validated
- [x] Module interdependencies verified
- [x] Documentation complete and reviewed
- [x] Backup and recovery systems operational
- [x] Provider version constraints properly configured

### 2. Environmental Prerequisites

#### Required Tools and Versions
- **Terraform**: >= 1.5.0 (tested with 1.5.0, 1.6.0, 1.7.0)
- **Google Cloud SDK**: Latest version
- **kubectl**: Latest stable version
- **Access Requirements**: 
  - GCP Project Owner or Editor permissions
  - Kubernetes cluster admin access
  - Terraform state bucket write permissions

#### Authentication Setup
```bash
# Service Account Authentication (Recommended)
export GOOGLE_APPLICATION_CREDENTIALS="/path/to/service-account-key.json"

# Or Workload Identity (for CI/CD)
gcloud auth login --cred-file=/path/to/workload-identity-token
```

## Deployment Phases

### Phase 1: Pre-Deployment Validation (15 minutes)

#### 1.1 Environment Verification
```bash
# Run comprehensive test suite
./scripts/run_all_tests.sh

# Validate specific environment
export ENVIRONMENT="production"
./scripts/test_terraform.sh
```

#### 1.2 Backup Current State
```bash
# Create comprehensive backup
./scripts/backup_terraform_state.sh production

# Validate backup integrity
./scripts/validate_state_backup.sh
```

#### 1.3 Resource Inventory
```bash
# Document current resources
terraform state list > pre-deployment-resources.txt
terraform show -json > pre-deployment-state.json
```

### Phase 2: Blue-Green Preparation (30 minutes)

#### 2.1 Infrastructure Planning
```bash
# Generate deployment plan
terraform plan -var-file=production.tfvars -out=production-upgrade.tfplan

# Review plan for unexpected changes
terraform show production-upgrade.tfplan > deployment-plan-review.txt
```

#### 2.2 Canary Environment Setup
```bash
# Deploy canary infrastructure first
terraform apply -target=module.proxy_networking_canary production-upgrade.tfplan
terraform apply -target=module.proxy_gke_canary production-upgrade.tfplan
```

#### 2.3 Health Check Validation
```bash
# Validate canary deployment
./scripts/validate_canary_deployment.sh
```

### Phase 3: Production Deployment (45 minutes)

#### 3.1 Rolling Update Strategy
```bash
# Deploy in specific order to minimize impact
# 1. Security components (IAM, KMS)
terraform apply -target=module.proxy.google_service_account.proxy_service_account
terraform apply -target=module.proxy.google_kms_key_ring.proxy_key_ring
terraform apply -target=module.proxy.google_kms_crypto_key.proxy_key

# 2. Storage components
terraform apply -target=module.proxy.google_storage_bucket.proxy_certificate

# 3. Networking components
terraform apply -target=module.proxy.proxy_networking

# 4. Compute components (GKE)
terraform apply -target=module.proxy.proxy_gke_americas
terraform apply -target=module.proxy.proxy_gke_emea  
terraform apply -target=module.proxy.proxy_gke_apac
```

#### 3.2 Service Validation
```bash
# Validate each component after deployment
./scripts/validate_production_deployment.sh
```

### Phase 4: Post-Deployment Validation (15 minutes)

#### 4.1 Comprehensive Health Checks
```bash
# Run full validation suite
./scripts/run_all_tests.sh

# Validate service endpoints
curl -f https://epp.example.com/health
curl -f https://whois.example.com/health
```

#### 4.2 Monitoring and Alerting
```bash
# Verify monitoring systems
gcloud monitoring dashboards list
gcloud logging sinks list
```

## Rollback Procedures

### Emergency Rollback (5 minutes)

#### Immediate Service Restoration
```bash
# 1. Restore previous Terraform state
./scripts/restore_terraform_state.sh <backup-timestamp>

# 2. Apply previous configuration
terraform apply -auto-approve

# 3. Validate service restoration
./scripts/validate_rollback.sh
```

### Selective Rollback (15 minutes)

#### Component-Specific Rollback
```bash
# Rollback specific components if needed
terraform apply -target=module.proxy.proxy_gke_americas -var="cluster_version=<previous-version>"

# Validate partial rollback
./scripts/validate_component_rollback.sh <component-name>
```

## Risk Mitigation

### High-Risk Areas

1. **GKE Cluster Updates**
   - **Risk**: Pod disruption during node pool updates
   - **Mitigation**: Use surge upgrade strategy with max_surge=1, max_unavailable=0
   - **Monitoring**: Track pod readiness and service availability

2. **Load Balancer Changes**
   - **Risk**: DNS propagation delays
   - **Mitigation**: Use blue-green deployment with traffic shifting
   - **Monitoring**: Monitor request success rates and latency

3. **KMS Key Rotation**
   - **Risk**: Encryption/decryption failures
   - **Mitigation**: Test key rotation in staging first
   - **Monitoring**: Track encryption operation success rates

### Monitoring and Alerting

#### Critical Metrics to Monitor
- Service availability (EPP, WHOIS endpoints)
- Request success rates (>99.9%)
- Response latency (<500ms p95)
- Error rates (<0.1%)
- Certificate expiration (>30 days remaining)

#### Alert Thresholds
```yaml
alerts:
  - name: "Service Unavailable"
    condition: "availability < 99.5%"
    duration: "2m"
    action: "immediate_rollback"
  
  - name: "High Error Rate"
    condition: "error_rate > 1%"
    duration: "5m"
    action: "investigate_and_rollback_if_needed"
```

## Communication Plan

### Stakeholder Notification

#### Pre-Deployment (24 hours before)
- **Recipients**: Engineering team, operations team, management
- **Content**: Deployment timeline, expected impact, contact information
- **Channels**: Email, Slack, JIRA

#### During Deployment
- **Recipients**: Operations team, on-call engineers
- **Content**: Real-time status updates, completion milestones
- **Channels**: Slack war room, monitoring dashboards

#### Post-Deployment (1 hour after)
- **Recipients**: All stakeholders
- **Content**: Deployment success confirmation, performance metrics
- **Channels**: Email summary, JIRA closure

### Emergency Contacts

- **Primary On-Call**: Engineering Lead
- **Secondary On-Call**: Senior DevOps Engineer  
- **Escalation**: Engineering Manager
- **Incident Commander**: Site Reliability Engineer

## Success Criteria

### Deployment Success Indicators
- [ ] All Terraform resources successfully applied
- [ ] All services responding to health checks
- [ ] No increase in error rates or latency
- [ ] All monitoring and alerting operational
- [ ] Backup and recovery systems validated

### Performance Benchmarks
- **Service Availability**: >99.9%
- **Response Time**: <500ms p95
- **Error Rate**: <0.1%
- **Recovery Time Objective (RTO)**: <5 minutes
- **Recovery Point Objective (RPO)**: <1 minute

## Post-Deployment Tasks

### Immediate (Day 1)
- [ ] Monitor service metrics for 24 hours
- [ ] Validate all backup systems
- [ ] Update monitoring dashboards
- [ ] Document any issues encountered

### Short-term (Week 1)
- [ ] Performance analysis and optimization
- [ ] Update runbooks and documentation
- [ ] Team retrospective meeting
- [ ] Plan next infrastructure improvements

### Long-term (Month 1)
- [ ] Cost analysis and optimization
- [ ] Security audit and compliance review
- [ ] Disaster recovery testing
- [ ] Capacity planning review

## Appendix

### Useful Commands Reference

```bash
# State management
terraform state list
terraform state show <resource>
terraform import <resource> <id>

# Plan analysis
terraform plan -detailed-exitcode
terraform show -json plan.tfplan | jq '.resource_changes'

# Debugging
terraform console
terraform refresh
TF_LOG=DEBUG terraform apply

# Validation
terraform validate
terraform fmt -check -recursive
terraform providers
```

### Emergency Response Playbook

1. **Service Down**
   ```bash
   # Check health endpoints
   curl -I https://epp.example.com/health
   curl -I https://whois.example.com/health
   
   # Check GKE cluster status
   kubectl get nodes
   kubectl get pods --all-namespaces
   
   # Immediate rollback if needed
   ./scripts/restore_terraform_state.sh <latest-backup>
   ```

2. **Degraded Performance**
   ```bash
   # Check resource utilization
   kubectl top nodes
   kubectl top pods
   
   # Check load balancer metrics
   gcloud compute backend-services describe <service-name>
   
   # Scale resources if needed
   kubectl scale deployment <deployment-name> --replicas=<number>
   ```

3. **Security Incident**
   ```bash
   # Rotate service account keys
   gcloud iam service-accounts keys create
   
   # Update KMS key rotation
   terraform apply -target=google_kms_crypto_key.proxy_key
   
   # Audit access logs
   gcloud logging read "resource.type=gce_instance"
   ```

---

**Document Version**: 1.0  
**Last Updated**: 2025-06-03  
**Next Review**: 2025-07-03  
**Owner**: Infrastructure Team  
**Approved By**: Engineering Manager