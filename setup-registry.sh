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

# TLD Registry Setup Script
# This script sets up the .tld registry with initial configuration
# Usage: ./setup-registry.sh [environment]

set -euo pipefail

# Configuration
ENVIRONMENT="${1:-newreg}"
PROJECT_ID="newreg-460918"

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
    
    # Check if gcloud is configured
    if ! gcloud config get-value project &>/dev/null; then
        log_error "gcloud is not configured. Please run 'gcloud auth login' and set your project."
        exit 1
    fi
    
    # Check if nom_build works
    if ! ./nom_build --help &>/dev/null; then
        log_error "nom_build is not working. Please ensure you're in the Nomulus directory."
        exit 1
    fi
    
    # Check if configuration files exist
    if [[ ! -f "nomulus-config-$ENVIRONMENT.yaml" ]]; then
        log_error "Configuration file nomulus-config-$ENVIRONMENT.yaml not found."
        exit 1
    fi
    
    if [[ ! -f "tld-config.yaml" ]]; then
        log_error "TLD configuration file tld-config.yaml not found."
        exit 1
    fi
    
    log_success "Prerequisites check passed"
}

# Build the project
build_project() {
    log_info "Building the Nomulus project..."
    ./nom_build build
    log_success "Project built successfully"
}

# Deploy database schema
deploy_schema() {
    log_info "Deploying database schema..."
    
    # Run Flyway migrations
    log_info "Running database migrations..."
    ./nom_build :db:flywayMigrate \
        --dbServer="google/postgres?cloudSqlInstance=$PROJECT_ID:us-central1:nomulus-db&socketFactory=com.google.cloud.sql.postgres.SocketFactory&useSSL=false" \
        --dbName=postgres \
        --dbUser=postgres
    
    log_success "Database schema deployed successfully"
}

# Set up TLD configuration
setup_tld() {
    log_info "Setting up .tld TLD configuration..."
    
    # Copy TLD config to temporary location for nomulus tool
    cp tld-config.yaml /tmp/tld-config.yaml
    
    # Create/update TLD using nomulus tool
    log_info "Creating .tld TLD..."
    ./nom_build :core:runCommand -- \
        --environment="$ENVIRONMENT" \
        configure_tld \
        --input=/tmp/tld-config.yaml
    
    log_success "TLD configuration completed"
}

# Create initial registrars
create_registrars() {
    log_info "Creating initial registrars..."
    
    # Create admin registrar
    log_info "Creating admin registrar..."
    ./nom_build :core:runCommand -- \
        --environment="$ENVIRONMENT" \
        create_registrar \
        admin-registrar \
        --name="TLD Registry Admin" \
        --password="admin123" \
        --allowed_tlds=tld \
        --registrar_type=REAL
    
    # Create test registrar
    log_info "Creating test registrar..."
    ./nom_build :core:runCommand -- \
        --environment="$ENVIRONMENT" \
        create_registrar \
        test-registrar \
        --name="Test Registrar Inc" \
        --password="test123" \
        --allowed_tlds=tld \
        --registrar_type=REAL
    
    # Create sample registrar
    log_info "Creating sample registrar..."
    ./nom_build :core:runCommand -- \
        --environment="$ENVIRONMENT" \
        create_registrar \
        sample-registrar \
        --name="Sample Domains LLC" \
        --password="sample123" \
        --allowed_tlds=tld \
        --registrar_type=REAL
    
    log_success "Registrars created successfully"
}

# Create admin contact
create_admin_contact() {
    log_info "Creating admin contact..."
    
    ./nom_build :core:runCommand -- \
        --environment="$ENVIRONMENT" \
        create_contact \
        --id=admin-contact \
        --name="Registry Administrator" \
        --org="TLD Registry" \
        --street="123 Registry St" \
        --city="Domain City" \
        --state="CA" \
        --zip="12345" \
        --country_code="US" \
        --phone="+1.5551234567" \
        --email="admin@registry.tld"
    
    log_success "Admin contact created"
}

# Deploy to App Engine
deploy_services() {
    log_info "Deploying services to App Engine..."
    
    # Deploy default service (frontend)
    log_info "Deploying default service..."
    ./nom_build :services:default:deploy --environment="$ENVIRONMENT"
    
    # Deploy backend service
    log_info "Deploying backend service..."
    ./nom_build :services:backend:deploy --environment="$ENVIRONMENT"
    
    # Deploy tools service
    log_info "Deploying tools service..."
    ./nom_build :services:tools:deploy --environment="$ENVIRONMENT"
    
    # Deploy console web app
    log_info "Deploying console web app..."
    ./nom_build :console-webapp:deploy --environment="$ENVIRONMENT"
    
    log_success "All services deployed successfully"
}

# Configure DNS (placeholder - requires manual setup)
configure_dns() {
    log_warning "DNS configuration requires manual setup:"
    echo "  1. Point registry.tld to your App Engine default service"
    echo "  2. Point whois.registry.tld to your WHOIS service"
    echo "  3. Point console.registry.tld to your console web app"
    echo "  4. Configure EPP endpoint at epp.registry.tld"
}

# Main execution
main() {
    echo "=========================================="
    echo "  TLD Registry Setup Script"
    echo "  Environment: $ENVIRONMENT"
    echo "  Project: $PROJECT_ID"
    echo "=========================================="
    echo ""
    
    check_prerequisites
    
    # Ask for confirmation
    log_warning "This will set up a complete .tld registry. Continue? (y/N)"
    read -r response
    if [[ ! "$response" =~ ^[Yy]$ ]]; then
        log_info "Setup cancelled by user"
        exit 0
    fi
    
    build_project
    deploy_schema
    setup_tld
    create_registrars
    create_admin_contact
    deploy_services
    configure_dns
    
    echo ""
    log_success "TLD Registry setup completed!"
    echo ""
    log_info "Next steps:"
    echo "  1. Configure DNS settings as shown above"
    echo "  2. Set up SSL certificates for your domains"
    echo "  3. Configure OAuth for console access"
    echo "  4. Test EPP connectivity"
    echo "  5. Access the registrar console at: https://console.registry.tld"
    echo ""
    log_info "Default credentials:"
    echo "  Admin Registrar: admin-registrar / admin123"
    echo "  Test Registrar: test-registrar / test123"
    echo "  Sample Registrar: sample-registrar / sample123"
}

# Run main function
main "$@"