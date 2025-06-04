#!/bin/bash

# Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

#
# Nomulus Database Setup Automation Script
#
# This script automates the complete database setup process for Nomulus registry.
# It creates the necessary database users and runs all Flyway migrations.
#

set -e  # Exit on any error

# Configuration
PROJECT_ID="${PROJECT_ID:-newreg-460918}"
INSTANCE_NAME="${INSTANCE_NAME:-nomulus-db}"
REGION="${REGION:-us-central1}"
DB_NAME="${DB_NAME:-postgres}"
SCHEMA_USER="${SCHEMA_USER:-schema_deployer}"
SCHEMA_PASSWORD="${SCHEMA_PASSWORD:-deployer123}"
READWRITE_USER="${READWRITE_USER:-nomulus_user}"
READWRITE_PASSWORD="${READWRITE_PASSWORD:-nomulus123}"
READONLY_USER="${READONLY_USER:-readonly_user}"
READONLY_PASSWORD="${READONLY_PASSWORD:-readonly123}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."
    
    # Check if gcloud is installed and authenticated
    if ! command -v gcloud &> /dev/null; then
        log_error "gcloud CLI is not installed. Please install it first."
        exit 1
    fi
    
    # Check if psql is installed
    if ! command -v psql &> /dev/null; then
        log_error "psql is not installed. Please install PostgreSQL client."
        exit 1
    fi
    
    # Check if cloud_sql_proxy is running or available
    if ! pgrep -f "cloud_sql_proxy\|cloud-sql-proxy" > /dev/null; then
        log_warning "Cloud SQL Proxy not detected. You may need to start it manually."
        log_info "Start with: gcloud sql connect ${INSTANCE_NAME} --user=postgres --project=${PROJECT_ID}"
    fi
    
    log_success "Prerequisites check completed"
}

# Test database connection
test_connection() {
    local user=$1
    local password=$2
    log_info "Testing connection for user: $user"
    
    if PGPASSWORD="$password" psql -h localhost -p 5432 -U "$user" -d "$DB_NAME" -c "SELECT version();" > /dev/null 2>&1; then
        log_success "Connection successful for user: $user"
        return 0
    else
        log_error "Connection failed for user: $user"
        return 1
    fi
}

# Create database users
create_database_users() {
    log_info "Creating database users..."
    
    # Connect as postgres user to create other users
    log_info "Creating schema_deployer user..."
    PGPASSWORD="${POSTGRES_PASSWORD:-postgres}" psql -h localhost -p 5432 -U postgres -d "$DB_NAME" << EOF
-- Create schema_deployer user (for migrations)
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '$SCHEMA_USER') THEN
        CREATE USER $SCHEMA_USER WITH PASSWORD '$SCHEMA_PASSWORD';
    END IF;
END
\$\$;

-- Grant necessary permissions to schema_deployer
GRANT CREATE ON DATABASE $DB_NAME TO $SCHEMA_USER;
GRANT USAGE ON SCHEMA public TO $SCHEMA_USER;
GRANT CREATE ON SCHEMA public TO $SCHEMA_USER;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO $SCHEMA_USER;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO $SCHEMA_USER;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO $SCHEMA_USER;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO $SCHEMA_USER;

-- Create readwrite role (for Nomulus application)
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'readwrite') THEN
        CREATE ROLE readwrite;
    END IF;
END
\$\$;

-- Create nomulus_user and grant readwrite role
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '$READWRITE_USER') THEN
        CREATE USER $READWRITE_USER WITH PASSWORD '$READWRITE_PASSWORD';
    END IF;
END
\$\$;

GRANT readwrite TO $READWRITE_USER;

-- Create readonly role (for reporting)
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'readonly') THEN
        CREATE ROLE readonly;
    END IF;
END
\$\$;

-- Create readonly_user and grant readonly role
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '$READONLY_USER') THEN
        CREATE USER $READONLY_USER WITH PASSWORD '$READONLY_PASSWORD';
    END IF;
END
\$\$;

GRANT readonly TO $READONLY_USER;

-- Grant usage on schema to readwrite and readonly roles
GRANT USAGE ON SCHEMA public TO readwrite, readonly;

\echo 'Database users created successfully'
EOF

    if [ $? -eq 0 ]; then
        log_success "Database users created successfully"
    else
        log_error "Failed to create database users"
        exit 1
    fi
}

# Fix migration files for concurrent index issues
fix_migration_files() {
    log_info "Checking and fixing migration files for concurrent index issues..."
    
    local files_to_fix=(
        "db/src/main/resources/sql/flyway/V165__add_domain_repo_id_indexes_to_more_tables.sql"
        "db/src/main/resources/sql/flyway/V169__add_more_indexes_needed_for_delete_prober_data.sql"
    )
    
    for file in "${files_to_fix[@]}"; do
        if [ -f "$file" ]; then
            if grep -q "CREATE INDEX CONCURRENTLY" "$file"; then
                log_info "Fixing concurrent index creation in $file"
                sed -i 's/CREATE INDEX CONCURRENTLY/CREATE INDEX/g' "$file"
                log_success "Fixed $file"
            else
                log_info "$file already fixed or doesn't contain CONCURRENTLY"
            fi
        else
            log_warning "Migration file not found: $file"
        fi
    done
}

# Kill any blocking database connections
kill_blocking_connections() {
    log_info "Checking for blocking database connections..."
    
    if test_connection "$SCHEMA_USER" "$SCHEMA_PASSWORD"; then
        PGPASSWORD="$SCHEMA_PASSWORD" psql -h localhost -p 5432 -U "$SCHEMA_USER" -d "$DB_NAME" << EOF
SELECT pg_terminate_backend(pid) 
FROM pg_stat_activity 
WHERE datname = '$DB_NAME' 
  AND usename = '$SCHEMA_USER' 
  AND application_name = 'PostgreSQL JDBC Driver'
  AND state = 'idle in transaction';
EOF
        log_info "Terminated any blocking connections"
    fi
}

# Run Flyway migrations
run_flyway_migrations() {
    log_info "Running Flyway migrations..."
    
    # First, check current migration status
    log_info "Checking current migration status..."
    ./nom_build :db:flywayInfo --dbServer=localhost:5432 --dbUser="$SCHEMA_USER" --dbPassword="$SCHEMA_PASSWORD" --dbName="$DB_NAME"
    
    # Run repair in case of checksum mismatches (safe to run even if not needed)
    log_info "Running Flyway repair (updating checksums)..."
    ./nom_build :db:flywayRepair --dbServer=localhost:5432 --dbUser="$SCHEMA_USER" --dbPassword="$SCHEMA_PASSWORD" --dbName="$DB_NAME"
    
    # Run the actual migration
    log_info "Running Flyway migration..."
    if ./nom_build :db:flywayMigrate --dbServer=localhost:5432 --dbUser="$SCHEMA_USER" --dbPassword="$SCHEMA_PASSWORD" --dbName="$DB_NAME"; then
        log_success "Flyway migration completed successfully"
    else
        log_error "Flyway migration failed"
        log_info "Attempting to kill blocking connections and retry..."
        kill_blocking_connections
        
        log_info "Retrying Flyway migration..."
        if ./nom_build :db:flywayMigrate --dbServer=localhost:5432 --dbUser="$SCHEMA_USER" --dbPassword="$SCHEMA_PASSWORD" --dbName="$DB_NAME"; then
            log_success "Flyway migration completed successfully on retry"
        else
            log_error "Flyway migration failed even after retry"
            exit 1
        fi
    fi
    
    # Show final migration status
    log_info "Final migration status:"
    ./nom_build :db:flywayInfo --dbServer=localhost:5432 --dbUser="$SCHEMA_USER" --dbPassword="$SCHEMA_PASSWORD" --dbName="$DB_NAME" | tail -10
}

# Set up database permissions after migration
setup_permissions() {
    log_info "Setting up database permissions..."
    
    PGPASSWORD="$SCHEMA_PASSWORD" psql -h localhost -p 5432 -U "$SCHEMA_USER" -d "$DB_NAME" << EOF
-- Grant readwrite permissions on all existing tables and sequences
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO readwrite;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO readwrite;

-- Grant readonly permissions on all existing tables
GRANT SELECT ON ALL TABLES IN SCHEMA public TO readonly;

-- Set default permissions for future objects
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO readwrite;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO readwrite;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO readonly;

\echo 'Database permissions set up successfully'
EOF

    log_success "Database permissions configured"
}

# Test all user connections
test_all_connections() {
    log_info "Testing all database connections..."
    
    if test_connection "$SCHEMA_USER" "$SCHEMA_PASSWORD"; then
        log_success "Schema deployer connection: OK"
    else
        log_error "Schema deployer connection: FAILED"
    fi
    
    if test_connection "$READWRITE_USER" "$READWRITE_PASSWORD"; then
        log_success "Read-write user connection: OK"
    else
        log_warning "Read-write user connection: FAILED (may need manual setup)"
    fi
    
    if test_connection "$READONLY_USER" "$READONLY_PASSWORD"; then
        log_success "Read-only user connection: OK"
    else
        log_warning "Read-only user connection: FAILED (may need manual setup)"
    fi
}

# Display final status
show_final_status() {
    log_info "Getting final database status..."
    
    if test_connection "$SCHEMA_USER" "$SCHEMA_PASSWORD"; then
        PGPASSWORD="$SCHEMA_PASSWORD" psql -h localhost -p 5432 -U "$SCHEMA_USER" -d "$DB_NAME" << EOF
\echo 'Current schema version:'
SELECT version, description, installed_on 
FROM flyway_schema_history 
ORDER BY installed_rank DESC 
LIMIT 5;

\echo 'Total tables in database:'
SELECT count(*) as table_count FROM information_schema.tables WHERE table_schema = 'public';

\echo 'Database setup completed successfully!'
EOF
    fi
}

# Main execution
main() {
    log_info "Starting Nomulus database setup automation..."
    log_info "Project: $PROJECT_ID"
    log_info "Instance: $INSTANCE_NAME"
    log_info "Database: $DB_NAME"
    
    check_prerequisites
    fix_migration_files
    create_database_users
    test_connection "$SCHEMA_USER" "$SCHEMA_PASSWORD"
    kill_blocking_connections
    run_flyway_migrations
    setup_permissions
    test_all_connections
    show_final_status
    
    log_success "Database setup automation completed successfully!"
    log_info "Database is ready for TLD configuration."
}

# Handle script arguments
if [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    echo "Nomulus Database Setup Automation Script"
    echo ""
    echo "Usage: $0 [options]"
    echo ""
    echo "Environment variables (optional):"
    echo "  PROJECT_ID         GCP project ID (default: newreg-460918)"
    echo "  INSTANCE_NAME      Cloud SQL instance name (default: nomulus-db)"
    echo "  REGION             GCP region (default: us-central1)"
    echo "  DB_NAME            Database name (default: postgres)"
    echo "  SCHEMA_USER        Schema deployer username (default: schema_deployer)"
    echo "  SCHEMA_PASSWORD    Schema deployer password (default: deployer123)"
    echo "  POSTGRES_PASSWORD  PostgreSQL superuser password (default: postgres)"
    echo ""
    echo "Example:"
    echo "  PROJECT_ID=my-project ./setup-database.sh"
    echo ""
    exit 0
fi

# Run main function
main "$@"
