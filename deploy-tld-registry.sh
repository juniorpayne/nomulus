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

# TLD Registry Deployment Script
# This script handles building and deploying the registry to App Engine
# Usage: ./deploy-tld-registry.sh [build|deploy|full] [environment]

set -euo pipefail

# Configuration
ACTION="${1:-full}"
ENVIRONMENT="${2:-newreg}"
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

# Usage information
usage() {
    echo "Usage: $0 [build|deploy|full] [environment]"
    echo ""
    echo "Actions:"
    echo "  build    - Build the project only"
    echo "  deploy   - Deploy to App Engine (assumes already built)"
    echo "  full     - Build and deploy (default)"
    echo ""
    echo "Environment:"
    echo "  newreg   - Deploy to newreg environment (default)"
    echo ""
    echo "Examples:"
    echo "  $0 build"
    echo "  $0 deploy"
    echo "  $0 full newreg"
    exit 1
}

# Set up gcloud project
setup_gcloud() {
    log_info "Setting up gcloud configuration..."
    gcloud config set project "$PROJECT_ID"
    log_success "gcloud configured for project: $PROJECT_ID"
}

# Build the project
build_project() {
    log_info "Building Nomulus project..."
    
    # Format code first
    log_info "Formatting code..."
    ./nom_build javaIncrementalFormatApply
    
    # Build all modules
    log_info "Building all modules..."
    ./nom_build build
    
    # Run tests
    log_info "Running tests..."
    ./nom_build test
    
    log_success "Build completed successfully"
}

# Deploy web applications
deploy_webapps() {
    log_info "Deploying web applications..."
    
    # Deploy console web app
    log_info "Building and deploying console web app..."
    cd console-webapp
    npm install
    npm run build:prod
    cd ..
    
    # Create deployment package for console
    log_info "Creating console deployment package..."
    mkdir -p console-webapp/staged/dist
    cp -r console-webapp/dist/* console-webapp/staged/
    
    # Deploy console to App Engine
    gcloud app deploy console-webapp/staged/app.yaml --quiet
    
    log_success "Web applications deployed"
}

# Deploy App Engine services
deploy_services() {
    log_info "Deploying App Engine services..."
    
    # Copy configuration file to WEB-INF directory
    log_info "Setting up configuration..."
    mkdir -p core/src/main/webapp/WEB-INF
    cp "nomulus-config-$ENVIRONMENT.yaml" core/src/main/webapp/WEB-INF/nomulus-config.yaml
    
    # Deploy default service (frontend)
    log_info "Deploying default service..."
    ./nom_build :services:default:stage
    gcloud app deploy services/default/build/staged-app/app.yaml --quiet
    
    # Deploy backend service
    log_info "Deploying backend service..."
    ./nom_build :services:backend:stage
    gcloud app deploy services/backend/build/staged-app/app.yaml --quiet
    
    # Deploy tools service
    log_info "Deploying tools service..."
    ./nom_build :services:tools:stage
    gcloud app deploy services/tools/build/staged-app/app.yaml --quiet
    
    # Deploy pubapi service
    log_info "Deploying pubapi service..."
    ./nom_build :services:pubapi:stage
    gcloud app deploy services/pubapi/build/staged-app/app.yaml --quiet
    
    log_success "All App Engine services deployed"
}

# Deploy proxy (if using GKE)
deploy_proxy() {
    log_info "Building proxy container..."
    
    # Build proxy Docker image
    cd proxy
    docker build -t "gcr.io/$PROJECT_ID/proxy:latest" .
    docker push "gcr.io/$PROJECT_ID/proxy:latest"
    cd ..
    
    log_info "Proxy container built and pushed"
    log_warning "Manual GKE deployment required for proxy service"
}

# Set up cron jobs and scheduled tasks
setup_scheduled_tasks() {
    log_info "Setting up scheduled tasks..."
    
    # Deploy cron configuration
    if [[ -f "core/src/main/java/google/registry/config/files/tasks/cloud-scheduler-tasks-$ENVIRONMENT.xml" ]]; then
        log_info "Deploying scheduled tasks..."
        gcloud app deploy "core/src/main/java/google/registry/config/files/tasks/cloud-scheduler-tasks-$ENVIRONMENT.xml" --quiet
    else
        log_warning "No scheduled tasks configuration found for environment: $ENVIRONMENT"
    fi
    
    log_success "Scheduled tasks configured"
}

# Verify deployment
verify_deployment() {
    log_info "Verifying deployment..."
    
    # Check App Engine services
    log_info "Checking App Engine services..."
    gcloud app services list
    
    # Test health endpoints
    log_info "Testing health endpoints..."
    DEFAULT_URL="https://default.${PROJECT_ID}.appspot.com"
    
    if curl -f "$DEFAULT_URL/_ah/health" > /dev/null 2>&1; then
        log_success "Default service health check passed"
    else
        log_warning "Default service health check failed"
    fi
    
    log_success "Deployment verification completed"
}

# Main execution
main() {
    echo "=========================================="
    echo "  TLD Registry Deployment"
    echo "  Action: $ACTION"
    echo "  Environment: $ENVIRONMENT" 
    echo "  Project: $PROJECT_ID"
    echo "=========================================="
    echo ""
    
    # Validate action
    case "$ACTION" in
        build|deploy|full)
            ;;
        *)
            log_error "Invalid action: $ACTION"
            usage
            ;;
    esac
    
    setup_gcloud
    
    case "$ACTION" in
        build)
            build_project
            ;;
        deploy)
            deploy_webapps
            deploy_services
            setup_scheduled_tasks
            verify_deployment
            ;;
        full)
            build_project
            deploy_webapps
            deploy_services
            setup_scheduled_tasks
            verify_deployment
            ;;
    esac
    
    echo ""
    log_success "Deployment action '$ACTION' completed!"
    echo ""
    log_info "Service URLs:"
    echo "  Default: https://default.$PROJECT_ID.appspot.com"
    echo "  Backend: https://backend.$PROJECT_ID.appspot.com"
    echo "  Tools: https://tools.$PROJECT_ID.appspot.com"
    echo "  Console: https://console.$PROJECT_ID.appspot.com"
    echo "  PubAPI: https://pubapi.$PROJECT_ID.appspot.com"
}

# Handle help flag
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
fi

# Run main function
main "$@"
