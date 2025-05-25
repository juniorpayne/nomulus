# TLD Registry Deployment Log

## Overview

This document records the complete process of setting up a `.tld` registry using Nomulus on Google Cloud Platform, from infrastructure provisioning to service deployment.

## Phase 1: Infrastructure Setup ✅ COMPLETED

### 1.1 GCP Project Creation
- **Project ID**: `newreg-460918`
- **Project Number**: `680343905479`
- **Region**: `us-central1`

### 1.2 Infrastructure Provisioning
**Command executed:**
```bash
./setup-nomulus-gcp.sh newreg-460918
```

**Resources created:**
- ✅ **Cloud SQL**: PostgreSQL 17 instance (`nomulus-db`) with `db-perf-optimized-N-2` tier
- ✅ **Storage Buckets**: 13 buckets for registry operations
  - `newreg-460918-billing/`
  - `newreg-460918-cert-bucket/`
  - `newreg-460918-commits/`
  - `newreg-460918-domain-lists/`
  - `newreg-460918-gcs-logs/`
  - `newreg-460918-icann-brda/`
  - `newreg-460918-icann-zfa/`
  - `newreg-460918-rde/`
  - `newreg-460918-reporting/`
  - `newreg-460918-snapshots/`
  - `newreg-460918-tfstate/`
  - `newreg-460918.appspot.com/`
  - `staging.newreg-460918.appspot.com/`

- ✅ **Service Accounts**: 4 accounts with proper IAM roles
  - `proxy-service-account@newreg-460918.iam.gserviceaccount.com`
  - `sql-proxy@newreg-460918.iam.gserviceaccount.com`
  - `newreg-460918@appspot.gserviceaccount.com` (App Engine default)
  - `680343905479-compute@developer.gserviceaccount.com` (Compute default)

- ✅ **KMS Resources**: 2 keyrings with encryption keys
  - `nomulus-keyring` with `nomulus-ssl-key`
  - `proxy-key-ring` with `proxy-key`

- ✅ **APIs**: 43 Google Cloud APIs enabled
- ✅ **App Engine**: Initialized in `us-central1` region

### 1.3 Issues Resolved During Infrastructure Setup
1. **SQL Tier Compatibility**: Updated from deprecated `db-custom-2-8192` to `db-perf-optimized-N-2` for Enterprise Plus edition
2. **Binary Log Issue**: Removed MySQL-only `--enable-bin-log` flag for PostgreSQL
3. **Database Creation**: Confirmed Nomulus uses default `postgres` database, removed unnecessary `nomulus-db` database creation

## Phase 2: Configuration Files Created ✅ COMPLETED

### 2.1 Main Registry Configuration
**File**: `nomulus-config-newreg.yaml`

**Key configurations:**
- Project ID: `newreg-460918`
- Base domain: `registry.tld`
- Database connection: Cloud SQL PostgreSQL instance
- Email configuration: `registry.tld` domain
- Service accounts: Proper IAM bindings
- Monitoring: Stackdriver integration

### 2.2 TLD Configuration
**File**: `tld-config.yaml`

**Key settings:**
- TLD: `.tld`
- Type: `REAL` (production ready)
- Pricing: $10 create/renew, $100 restore
- Currency: USD
- State: `GENERAL_AVAILABILITY`
- Grace periods: 5 days standard, 30 days redemption
- DNS: Initially configured with `VoidDnsWriter` for testing

### 2.3 Automation Scripts
**Files created:**
1. **`setup-registry.sh`** - Complete registry setup script
2. **`deploy-tld-registry.sh`** - Service deployment automation

## Phase 3: Application Build ✅ COMPLETED

### 3.1 Build Process
**Command executed:**
```bash
./nom_build build
```

**Build results:**
- ✅ **Core modules**: Built successfully
- ✅ **Service staging**: All services staged for deployment
  - `services/default/build/staged-app/`
  - `services/backend/build/staged-app/`
  - `services/tools/build/staged-app/`
  - `services/bsa/build/staged-app/`
  - `services/pubapi/build/staged-app/`
- ✅ **Console webapp**: Built for multiple environments
  - `console-webapp/staged/` ready for deployment
- ✅ **Docker images**: `proxy` and `nomulus-tool` containers built

### 3.2 Build Components Verified
- Java compilation: ✅ All modules compiled
- Frontend build: ✅ Angular console app built
- Docker images: ✅ Container images created
- WAR files: ✅ App Engine deployable packages ready

## Phase 4: Service Deployment ✅ COMPLETED

### 4.1 Default Service Deployment
**Command executed:**
```bash
cd services/default/build/staged-app && gcloud app deploy app.yaml --quiet
```

**Result:**
- ✅ **Status**: Successfully deployed
- ✅ **URL**: https://newreg-460918.uc.r.appspot.com
- ✅ **Service**: `default` with 1 version

**Functionality provided:**
- EPP endpoint for domain registration
- WHOIS service for domain queries
- RDAP service for structured domain data
- Frontend web interface

### 4.2 Console Service Deployment
**Command executed:**
```bash
cd console-webapp/staged && gcloud app deploy app.yaml --quiet
```

**Result:**
- ✅ **Status**: Successfully deployed  
- ✅ **URL**: https://console-dot-newreg-460918.uc.r.appspot.com
- ✅ **Service**: `console` with 1 version

**Functionality provided:**
- Registrar management console
- Domain registration interface
- Administrative tools
- User management

### 4.3 Certificate Issue Resolution
**Problem identified:** User reported certificate error with `console.newreg-460918.appspot.com`

**Root cause:** Incorrect URL format - App Engine uses `console-dot-projectid.uc.r.appspot.com` format

**Resolution:** Provided correct URLs:
- **Default service**: https://newreg-460918.uc.r.appspot.com
- **Console service**: https://console-dot-newreg-460918.uc.r.appspot.com

## Current Status: READY FOR REGISTRY SETUP

### ✅ Completed Components
1. **Infrastructure**: All GCP resources provisioned and configured
2. **Configuration**: Registry and TLD config files created
3. **Build**: Application successfully built and staged
4. **Deployment**: Core services deployed and accessible

### 🔄 Next Steps Required
1. **Database Schema**: Deploy database schema and create TLD
2. **Registrars**: Create initial registrars for testing
3. **Authentication**: Configure OAuth for console access (if needed)
4. **DNS**: Configure production DNS settings

## Commands Ready for Execution

### Registry Setup (Database + TLD + Registrars)
```bash
./setup-registry.sh newreg
```

**This script will:**
- Deploy database schema using Flyway migrations
- Create the `.tld` TLD configuration in the database
- Create 3 initial registrars:
  - `admin-registrar` (password: `admin123`)
  - `test-registrar` (password: `test123`)  
  - `sample-registrar` (password: `sample123`)
- Create admin contact for registry operations

### Additional Services Deployment (Optional)
```bash
./deploy-tld-registry.sh deploy newreg
```

**This will deploy:**
- Backend service (scheduled tasks, batch operations)
- Tools service (administrative APIs)
- PubAPI service (public API endpoints)
- BSA service (Brand Security Alliance integration)

## Service URLs

| Service | URL | Status |
|---------|-----|--------|
| **Default** (EPP/WHOIS/RDAP) | https://newreg-460918.uc.r.appspot.com | ✅ **DEPLOYED** |
| **Console** (Registrar UI) | https://console-dot-newreg-460918.uc.r.appspot.com | ✅ **DEPLOYED** |
| Backend | https://backend-dot-newreg-460918.uc.r.appspot.com | ⏳ Pending |
| Tools | https://tools-dot-newreg-460918.uc.r.appspot.com | ⏳ Pending |
| PubAPI | https://pubapi-dot-newreg-460918.uc.r.appspot.com | ⏳ Pending |
| BSA | https://bsa-dot-newreg-460918.uc.r.appspot.com | ⏳ Pending |

## Configuration Files Summary

| File | Purpose | Status |
|------|---------|--------|
| `nomulus-config-newreg.yaml` | Main registry configuration | ✅ Created |
| `tld-config.yaml` | TLD-specific settings | ✅ Created |
| `setup-registry.sh` | Registry setup automation | ✅ Created |
| `deploy-tld-registry.sh` | Service deployment automation | ✅ Created |
| `TLD-REGISTRY-SETUP.md` | Complete setup documentation | ✅ Created |

## Infrastructure Summary

| Component | Details | Status |
|-----------|---------|--------|
| **GCP Project** | newreg-460918 (us-central1) | ✅ Active |
| **Cloud SQL** | PostgreSQL 17, db-perf-optimized-N-2 | ✅ Running |
| **Storage** | 13 GCS buckets for registry operations | ✅ Created |
| **KMS** | 2 keyrings with encryption keys | ✅ Active |
| **IAM** | 4 service accounts with proper roles | ✅ Configured |
| **APIs** | 43 Google Cloud APIs enabled | ✅ Enabled |
| **App Engine** | Platform initialized | ✅ Ready |

## Ready for Registry Operations

The infrastructure and core services are now deployed and ready. The next step is to run the registry setup script to initialize the database schema, create the `.tld` TLD, and set up initial registrars for testing.

**Current registry state**: Infrastructure ready, services deployed, awaiting TLD configuration and registrar setup.