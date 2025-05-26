# Nomulus Database Setup Progress

## Overview
This document tracks the progress of setting up the Nomulus registry database for the `newreg-460918` project.

## Current Status: ✅ COMPLETED
- **Database Instance**: Cloud SQL PostgreSQL (newreg-460918:us-central1:nomulus-db)
- **Schema Version**: 194 (fully migrated)
- **Connection**: Local proxy tunnel established
- **Users**: schema_deployer user created and configured

## Steps Completed

### 1. Database Instance Creation ✅
- Created Cloud SQL PostgreSQL instance: `newreg-460918:us-central1:nomulus-db`
- Configured with appropriate machine type and storage
- Enabled connection from local environment

### 2. Database User Setup ✅
- Created `schema_deployer` user with password `deployer123`
- Granted necessary permissions for schema management
- Verified connection: `psql -h localhost -p 5432 -U schema_deployer -d postgres`

### 3. Local Connection Setup ✅
- Established Cloud SQL Proxy tunnel on localhost:5432
- Verified connectivity using psql command
- Connection string: `jdbc:postgresql://localhost:5432/postgres`

### 4. Flyway Migration Issues Resolved ✅
**Problem**: Concurrent index creation was blocking within transactions
```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS IDX... -- Cannot run in transactions
```

**Solution**: Modified migration files to remove `CONCURRENTLY` keyword:
- `V165__add_domain_repo_id_indexes_to_more_tables.sql`
- `V169__add_more_indexes_needed_for_delete_prober_data.sql`

**Process**:
1. Removed `CONCURRENTLY` from index creation statements
2. Ran `flywayRepair` to fix checksums for modified files
3. Completed migration successfully

### 5. Schema Deployment ✅
- **Command Used**: `./nom_build :db:flywayMigrate --dbServer=localhost:5432 --dbUser=schema_deployer --dbPassword=deployer123 --dbName=postgres`
- **Result**: All 194 migrations applied successfully
- **Final Version**: V194 (password reset request registrar)
- **Total Migration Time**: ~30 minutes (initial run with blocking issues)

## Database Configuration Details

### Connection Parameters
```bash
Host: localhost (via Cloud SQL Proxy)
Port: 5432
Database: postgres
Username: schema_deployer
Password: deployer123
```

### Flyway Configuration Location
- Build file: `db/build.gradle`
- Migration scripts: `db/src/main/resources/sql/flyway/`
- Configuration function: `getJdbcAccessInfo()` in build.gradle

### Schema Verification
```sql
-- Check migration status
SELECT version, description, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;

-- Current version: 194
-- Last migration: 2025-05-26 08:52:20.208126
```

## Issues Encountered and Solutions

### Issue 1: Concurrent Index Creation Blocking
**Error**: `CREATE INDEX CONCURRENTLY` getting blocked by idle transactions
**Root Cause**: Flyway runs in transactional context, concurrent operations cannot proceed
**Solution**: Changed to regular `CREATE INDEX IF NOT EXISTS` for initial schema setup

### Issue 2: Migration Checksum Mismatch
**Error**: `Migration checksum mismatch for migration version 165`
**Root Cause**: Modified migration files after they were already applied
**Solution**: Used `flywayRepair` command to update checksums

### Issue 3: Long-Running Transactions
**Error**: Processes stuck in "idle in transaction" state
**Solution**: Terminated blocking connections using `pg_terminate_backend()`

## Next Steps
1. ✅ Database schema fully initialized
2. 🔄 Configure TLD using `configure_tld` command  
3. ⏳ Create initial registrars (admin-registrar, test-registrar)
4. ⏳ Set up registry configuration

## Commands Reference

### Database Connection Test
```bash
PGPASSWORD="deployer123" psql -h localhost -p 5432 -U schema_deployer -d postgres -c "SELECT version();"
```

### Flyway Operations
```bash
# Check migration status
./nom_build :db:flywayInfo --dbServer=localhost:5432 --dbUser=schema_deployer --dbPassword=deployer123 --dbName=postgres

# Run migrations
./nom_build :db:flywayMigrate --dbServer=localhost:5432 --dbUser=schema_deployer --dbPassword=deployer123 --dbName=postgres

# Repair checksums (if needed)
./nom_build :db:flywayRepair --dbServer=localhost:5432 --dbUser=schema_deployer --dbPassword=deployer123 --dbName=postgres
```

### Kill Blocking Connections
```bash
PGPASSWORD="deployer123" psql -h localhost -p 5432 -U schema_deployer -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'postgres' AND usename = 'schema_deployer' AND application_name = 'PostgreSQL JDBC Driver';"
```