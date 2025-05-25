# TLD Registry Setup Guide

This guide walks you through setting up a complete `.tld` registry using Nomulus on Google Cloud Platform.

## Overview

The setup creates:
- A functional `.tld` top-level domain registry
- Multiple App Engine services (frontend, backend, tools, console)
- Initial registrars for testing and administration
- Database schema and configuration
- Monitoring and reporting capabilities

## Prerequisites

Before starting, ensure you have:

1. **GCP Project**: Created using `./setup-nomulus-gcp.sh` 
2. **Authentication**: Logged in with `gcloud auth login`
3. **Dependencies**: Java 11+, Docker, Node.js 22.7.0
4. **Repository**: Cloned Nomulus repository with all files

## Configuration Files

### 1. Main Registry Configuration (`nomulus-config-newreg.yaml`)

This file configures the overall registry settings:
- GCP project details
- Service URLs and domain configuration  
- Email and authentication settings
- Database connection strings
- Billing and monitoring configuration

**Key settings for `.tld` registry:**
```yaml
gcpProject:
  projectId: newreg-460918
  baseDomain: registry.tld

gSuite:
  domainName: registry.tld
  adminAccountEmailAddress: admin@registry.tld

registryPolicy:
  productName: TLD Registry
  registryAdminClientId: admin-registrar
```

### 2. TLD Configuration (`tld-config.yaml`)

Defines the `.tld` TLD behavior:
- Grace periods and lifecycle settings
- Pricing structure ($10 create/renew, $100 restore)
- DNS configuration
- Premium and reserved lists
- Registry features (escrow, invoicing, etc.)

**Key settings:**
```yaml
tldStr: "tld"
tldType: "REAL"
currency: "USD"
createBillingCost:
  amount: 10.00
tldStateTransitions:
  "2025-05-25T18:00:00.000Z": "GENERAL_AVAILABILITY"
```

## Deployment Process

### Step 1: Infrastructure Setup

If not already done, set up the GCP infrastructure:

```bash
# Set up GCP project with all required resources
./setup-nomulus-gcp.sh newreg-460918
```

This creates:
- Cloud SQL PostgreSQL instance
- Storage buckets for various registry functions
- KMS keys for encryption
- Service accounts with proper IAM roles
- All required Google Cloud APIs

### Step 2: Registry Configuration

Set up the registry with TLD and registrars:

```bash
# Complete registry setup
./setup-registry.sh newreg
```

This script:
1. **Builds** the Nomulus project
2. **Deploys** database schema using Flyway migrations
3. **Creates** the `.tld` TLD with specified configuration
4. **Sets up** initial registrars:
   - `admin-registrar` - Registry administration
   - `test-registrar` - Testing and development  
   - `sample-registrar` - Sample registrar for demos
5. **Creates** admin contact for registry operations

### Step 3: Service Deployment

Deploy all services to App Engine:

```bash
# Full build and deployment
./deploy-tld-registry.sh full newreg
```

This deploys:
- **Default Service**: Frontend EPP, WHOIS, RDAP endpoints
- **Backend Service**: Scheduled tasks, batch operations
- **Tools Service**: Administrative tools and APIs
- **Console Service**: Web-based registrar console
- **PubAPI Service**: Public API endpoints

## Service URLs

After deployment, services are available at:

| Service | URL | Purpose |
|---------|-----|---------|
| Default | `https://default.newreg-460918.appspot.com` | EPP, WHOIS, RDAP |
| Backend | `https://backend.newreg-460918.appspot.com` | Background tasks |
| Tools | `https://tools.newreg-460918.appspot.com` | Admin tools |
| Console | `https://console.newreg-460918.appspot.com` | Registrar console |
| PubAPI | `https://pubapi.newreg-460918.appspot.com` | Public APIs |

## Initial Registrars

Three registrars are created for different purposes:

| Registrar ID | Name | Password | Purpose |
|--------------|------|----------|---------|
| `admin-registrar` | TLD Registry Admin | `admin123` | Registry administration |
| `test-registrar` | Test Registrar Inc | `test123` | Development and testing |
| `sample-registrar` | Sample Domains LLC | `sample123` | Demonstrations |

## Registry Features

### Domain Registration
- **Create**: $10.00 USD
- **Renew**: $10.00 USD  
- **Restore**: $100.00 USD
- **Grace Periods**: 5 days for most operations, 30 days for redemption

### DNS Management
- Currently configured with `VoidDnsWriter` (testing mode)
- Change to `CloudDnsWriter` for production DNS publishing
- Configurable TTL values for different record types

### Monitoring & Reporting
- **Registry Data Escrow**: Enabled for continuity requirements
- **ICANN Reporting**: Configured endpoints for compliance
- **Billing**: Automated invoicing and cost tracking
- **Monitoring**: Stackdriver integration for metrics

## Post-Deployment Tasks

### 1. DNS Configuration

Set up DNS records for your registry domains:

```bash
# Point these domains to your App Engine services:
registry.tld          → default.newreg-460918.appspot.com
whois.registry.tld    → default.newreg-460918.appspot.com  
console.registry.tld  → console.newreg-460918.appspot.com
epp.registry.tld      → default.newreg-460918.appspot.com
```

### 2. SSL Certificates

Configure SSL certificates for secure connections:
- Use Google-managed certificates for App Engine domains
- Configure custom certificates for your registry domains
- Update DNS configuration in TLD settings

### 3. OAuth Setup

Configure OAuth for console authentication:
1. Create OAuth 2.0 credentials in Google Cloud Console
2. Update `nomulus-config-newreg.yaml` with OAuth client ID
3. Configure authorized domains and redirect URIs

### 4. Production DNS

For production deployment:
1. Change `dnsWriters` in `tld-config.yaml` from `VoidDnsWriter` to `CloudDnsWriter`
2. Set up Cloud DNS zones for your TLD
3. Configure DNS publish locks and TTL values

## Testing the Registry

### 1. Console Access

Access the registrar console:
1. Navigate to `https://console.newreg-460918.appspot.com`
2. Log in with registrar credentials
3. Test domain registration and management functions

### 2. EPP Testing

Test EPP functionality:
```bash
# Connect to EPP endpoint
telnet epp.registry.tld 700

# Use EPP commands to test domain operations
<epp>...</epp>
```

### 3. WHOIS Testing

Test WHOIS service:
```bash
# Query WHOIS for a domain
whois -h whois.registry.tld example.tld
```

## Maintenance Operations

### Database Backups
- Automated backups configured for Cloud SQL
- Manual backups available through Cloud Console
- Point-in-time recovery enabled

### Monitoring
- Application performance monitoring via Stackdriver
- Custom metrics for registry operations
- Alerting for service availability and errors

### Updates
```bash
# Rebuild and redeploy after code changes
./deploy-tld-registry.sh full newreg

# Deploy only configuration changes
./setup-registry.sh newreg
```

## Troubleshooting

### Common Issues

1. **Build Failures**: Ensure Java 11+ and all dependencies installed
2. **Database Connection**: Verify Cloud SQL instance is running and accessible
3. **Service Deployment**: Check App Engine quotas and permissions
4. **Console Access**: Verify OAuth configuration and domain setup

### Logs and Debugging

View logs for troubleshooting:
```bash
# App Engine logs
gcloud app logs tail

# Cloud SQL logs  
gcloud sql operations list --instance=nomulus-db

# Check service status
gcloud app services list
gcloud app versions list
```

### Support Resources

- **Nomulus Documentation**: `/docs/` directory
- **Google Cloud Support**: Cloud Console support section
- **Registry Operations**: Monitor via Cloud Console dashboards

## Security Considerations

1. **Change Default Passwords**: Update registrar passwords from defaults
2. **OAuth Configuration**: Secure OAuth settings for console access  
3. **Database Security**: Configure Cloud SQL with private IP
4. **Network Security**: Use Cloud Armor for DDoS protection
5. **Monitoring**: Set up security monitoring and alerting

This completes the setup of a fully functional `.tld` registry with Nomulus!