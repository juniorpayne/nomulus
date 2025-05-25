# Nomulus GCP Resources Documentation

This document outlines all the GCP resources required for a Nomulus registry deployment, based on the existing `example-registry-alpha` project.

## Project Information
- **Project ID**: example-registry-alpha
- **Project Number**: 634234313610
- **Project Name**: Example Registry Alpha
- **Status**: ACTIVE

## Service Accounts

### Custom Service Accounts
1. **proxy-service-account@example-registry-alpha.iam.gserviceaccount.com**
   - Display Name: Nomulus proxy service account
   - Purpose: Used by the proxy service for logging and monitoring

2. **sql-proxy@example-registry-alpha.iam.gserviceaccount.com**
   - Purpose: Cloud SQL proxy service account

### Default Service Accounts
1. **example-registry-alpha@appspot.gserviceaccount.com**
   - App Engine default service account

2. **634234313610-compute@developer.gserviceaccount.com**
   - Compute Engine default service account

## IAM Roles and Bindings

### Key Custom Service Account Permissions
- **proxy-service-account**:
  - `roles/logging.logWriter`
  - `roles/monitoring.metricWriter`
  - `roles/storage.objectViewer`

- **sql-proxy**:
  - `roles/cloudsql.admin`

- **App Engine default service account**:
  - `roles/appengine.deployer`
  - `roles/cloudbuild.builds.editor`
  - `roles/cloudsql.client`
  - `roles/secretmanager.secretAccessor`
  - `roles/storage.admin`
  - `roles/vpcaccess.user`

- **Cloud Build service account**:
  - `roles/appengine.deployer`
  - `roles/cloudbuild.builds.builder`
  - `roles/cloudbuild.builds.editor`
  - `roles/storage.admin`

## Cloud Storage Buckets

All buckets are configured with:
- **Storage Class**: STANDARD
- **Location**: US (multi-region)
- **Bucket Policy Only**: Enabled
- **Public Access Prevention**: Inherited
- **Versioning**: Disabled

### Required Buckets
1. **{PROJECT_ID}-billing/**
   - Purpose: Billing-related data storage

2. **{PROJECT_ID}-cert-bucket/**
   - Purpose: Certificate storage

3. **{PROJECT_ID}-commits/**
   - Purpose: Git commit tracking

4. **{PROJECT_ID}-domain-lists/**
   - Purpose: Domain list storage (reserved, premium, etc.)

5. **{PROJECT_ID}-gcs-logs/**
   - Purpose: GCS access logs

6. **{PROJECT_ID}-icann-brda/**
   - Purpose: ICANN BRDA (Bulk Registration Data Access) reports

7. **{PROJECT_ID}-icann-zfa/**
   - Purpose: ICANN Zone File Access

8. **{PROJECT_ID}-rde/**
   - Purpose: Registry Data Escrow deposits

9. **{PROJECT_ID}-reporting/**
   - Purpose: Registry reporting data

10. **{PROJECT_ID}-snapshots/**
    - Purpose: Database and system snapshots

11. **{PROJECT_ID}-tfstate/**
    - Purpose: Terraform state files

12. **{PROJECT_ID}.appspot.com/**
    - Purpose: App Engine default bucket

13. **{PROJECT_ID}_cloudbuild/**
    - Purpose: Cloud Build artifacts

14. **staging.{PROJECT_ID}.appspot.com/**
    - Purpose: App Engine staging bucket
    - Has lifecycle configuration enabled

## Cloud SQL

### Instance Configuration
- **Instance Name**: nomulus-db
- **Region**: us-central1
- **Database Version**: POSTGRES_17
- **Databases**:
  - `postgres` (default)
  - `nomulus-db` (application database)

## Cloud KMS

### Key Rings
1. **nomulus-keyring** (global)
   - **Keys**:
     - `nomulus-ssl-key` (ENCRYPT_DECRYPT)

2. **proxy-key-ring** (global)
   - **Keys**:
     - `proxy-key` (ENCRYPT_DECRYPT)

## Enabled Google Cloud APIs

The following 43 APIs are enabled:

1. analyticshub.googleapis.com
2. appengine.googleapis.com
3. appenginereporting.googleapis.com
4. artifactregistry.googleapis.com
5. autoscaling.googleapis.com
6. bigquery.googleapis.com
7. bigqueryconnection.googleapis.com
8. bigquerydatapolicy.googleapis.com
9. bigquerymigration.googleapis.com
10. bigqueryreservation.googleapis.com
11. bigquerystorage.googleapis.com
12. cloudapis.googleapis.com
13. cloudbuild.googleapis.com
14. cloudkms.googleapis.com
15. cloudscheduler.googleapis.com
16. cloudtasks.googleapis.com
17. cloudtrace.googleapis.com
18. compute.googleapis.com
19. container.googleapis.com
20. containerfilesystem.googleapis.com
21. containerregistry.googleapis.com
22. dataform.googleapis.com
23. dataplex.googleapis.com
24. datastore.googleapis.com
25. deploymentmanager.googleapis.com
26. dns.googleapis.com
27. gkebackup.googleapis.com
28. iam.googleapis.com
29. iamcredentials.googleapis.com
30. logging.googleapis.com
31. monitoring.googleapis.com
32. networkconnectivity.googleapis.com
33. oslogin.googleapis.com
34. pubsub.googleapis.com
35. secretmanager.googleapis.com
36. servicemanagement.googleapis.com
37. serviceusage.googleapis.com
38. sql-component.googleapis.com
39. sqladmin.googleapis.com
40. storage-api.googleapis.com
41. storage-component.googleapis.com
42. storage.googleapis.com
43. vpcaccess.googleapis.com

## Notes

- All resources follow the naming pattern of `{PROJECT_ID}-{purpose}`
- Buckets use uniform bucket-level access (Bucket Policy Only)
- Service accounts have minimum required permissions
- KMS keys are used for encryption of sensitive data
- Cloud SQL instance uses PostgreSQL 17
- Multi-region US location is used for high availability