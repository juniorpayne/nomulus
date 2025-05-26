# Nomulus Database Setup Automation Guide

## Quick Start

The `setup-database.sh` script automates the entire database setup process. Simply run:

```bash
./setup-database.sh
```

## What the Script Does

### 1. Prerequisites Check
- Verifies `gcloud` CLI is installed and authenticated
- Checks for `psql` PostgreSQL client
- Warns if Cloud SQL Proxy is not running

### 2. Database User Creation
Creates three types of users with appropriate permissions:

- **`schema_deployer`** (password: `deployer123`)
  - Used for running Flyway migrations
  - Has full schema management permissions
  
- **`nomulus_user`** (password: `nomulus123`) 
  - Application user with `readwrite` role
  - Used by Nomulus services for normal operations
  
- **`readonly_user`** (password: `readonly123`)
  - Read-only access for reporting and monitoring
  - Has `readonly` role

### 3. Migration File Fixes
Automatically fixes known issues with migration files:
- Removes `CONCURRENTLY` keyword from index creation in V165 and V169
- These files caused blocking issues in Flyway's transactional context

### 4. Flyway Migration Execution
- Runs `flywayRepair` to handle checksum mismatches
- Executes all migrations from V1 to V194
- Handles blocking connections automatically
- Retries failed migrations after killing blocking processes

### 5. Permission Setup
- Grants appropriate table/sequence permissions to roles
- Sets up default privileges for future objects
- Configures read-write access for application user
- Configures read-only access for reporting user

### 6. Validation
- Tests connections for all created users
- Shows final schema version and table count
- Provides connection status summary

## Configuration Options

The script accepts environment variables for customization:

```bash
# Basic configuration
PROJECT_ID=your-project-id \
INSTANCE_NAME=your-instance-name \
REGION=your-region \
./setup-database.sh

# Full configuration example
PROJECT_ID=newreg-460918 \
INSTANCE_NAME=nomulus-db \
REGION=us-central1 \
DB_NAME=postgres \
SCHEMA_USER=schema_deployer \
SCHEMA_PASSWORD=deployer123 \
READWRITE_USER=nomulus_user \
READWRITE_PASSWORD=nomulus123 \
READONLY_USER=readonly_user \
READONLY_PASSWORD=readonly123 \
POSTGRES_PASSWORD=postgres \
./setup-database.sh
```

## Prerequisites

Before running the script, ensure:

1. **Cloud SQL Instance Created**: The PostgreSQL instance should already exist
2. **Cloud SQL Proxy Running**: Connection to localhost:5432 should be available
3. **PostgreSQL Client**: `psql` command should be available
4. **Google Cloud SDK**: `gcloud` should be installed and authenticated

### Starting Cloud SQL Proxy

```bash
# Method 1: Direct connection (recommended for automation)
gcloud sql connect nomulus-db --user=postgres --project=newreg-460918

# Method 2: Manual proxy (for development)
cloud_sql_proxy -instances=newreg-460918:us-central1:nomulus-db=tcp:5432
```

## Manual Operations

If you need to run operations manually:

### Check Migration Status
```bash
./nom_build :db:flywayInfo --dbServer=localhost:5432 --dbUser=schema_deployer --dbPassword=deployer123 --dbName=postgres
```

### Run Migrations Only
```bash
./nom_build :db:flywayMigrate --dbServer=localhost:5432 --dbUser=schema_deployer --dbPassword=deployer123 --dbName=postgres
```

### Test Connections
```bash
# Schema deployer
PGPASSWORD="deployer123" psql -h localhost -p 5432 -U schema_deployer -d postgres -c "SELECT version();"

# Application user  
PGPASSWORD="nomulus123" psql -h localhost -p 5432 -U nomulus_user -d postgres -c "SELECT current_user;"

# Read-only user
PGPASSWORD="readonly123" psql -h localhost -p 5432 -U readonly_user -d postgres -c "SELECT count(*) FROM information_schema.tables;"
```

## Troubleshooting

### Common Issues

**Issue**: Script fails with "Connection refused"
**Solution**: Ensure Cloud SQL Proxy is running and connected to localhost:5432

**Issue**: Permission denied errors
**Solution**: Verify you're authenticated with `gcloud auth login` and have proper IAM roles

**Issue**: Migration checksum mismatches
**Solution**: The script automatically runs `flywayRepair`, but you can run it manually if needed

**Issue**: Blocking transactions during migration
**Solution**: The script automatically detects and terminates blocking connections

### Logs and Debugging

The script provides colored output:
- 🔵 **Blue**: Informational messages
- 🟢 **Green**: Success messages  
- 🟡 **Yellow**: Warnings
- 🔴 **Red**: Errors

For more detailed debugging, check:
- Flyway logs in the Gradle output
- PostgreSQL logs via `gcloud sql operations list`
- Connection status via `pg_stat_activity` table

## Integration with CI/CD

The script is designed to be idempotent and can be safely run multiple times. It's suitable for:

- Development environment setup
- CI/CD pipeline database initialization
- Disaster recovery procedures
- Environment provisioning automation

## Security Considerations

- Default passwords are used for convenience in development
- Change passwords for production environments
- Consider using IAM authentication for Cloud SQL
- Rotate credentials regularly
- Use environment variables for sensitive values

## Next Steps

After successful database setup:

1. **Configure TLD**: Use `configure_tld` command to set up your domain
2. **Create Registrars**: Set up initial registrar accounts
3. **Deploy Services**: Deploy Nomulus application services
4. **Test EPP**: Verify domain registration functionality