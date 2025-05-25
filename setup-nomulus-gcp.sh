#!/bin/bash

# Nomulus GCP Project Setup Script
# This script provisions all required GCP resources for a new Nomulus registry deployment
# Usage: ./setup-nomulus-gcp.sh PROJECT_ID [REGION] [SQL_TIER]

set -euo pipefail

# Configuration
PROJECT_ID="${1:-}"
REGION="${2:-us-central1}"
SQL_TIER="${3:-db-perf-optimized-N-2}"  # 2 vCPUs, 16GB RAM (Enterprise Plus)
LOCATION="US"  # Multi-region location for buckets

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

# Usage function
usage() {
    echo "Usage: $0 PROJECT_ID [REGION] [SQL_TIER]"
    echo ""
    echo "Arguments:"
    echo "  PROJECT_ID    Required. The GCP project ID for the Nomulus deployment"
    echo "  REGION        Optional. The GCP region (default: us-central1)"
    echo "  SQL_TIER      Optional. Cloud SQL tier (default: db-perf-optimized-N-2)"
    echo ""
    echo "Examples:"
    echo "  $0 my-registry-prod"
    echo "  $0 my-registry-staging us-west1 db-perf-optimized-N-1"
    exit 1
}

# Validate arguments
if [[ -z "$PROJECT_ID" ]]; then
    log_error "PROJECT_ID is required"
    usage
fi

# Validate project ID format
if [[ ! "$PROJECT_ID" =~ ^[a-z][a-z0-9-]{4,28}[a-z0-9]$ ]]; then
    log_error "Invalid PROJECT_ID format. Must be 6-30 characters, start with lowercase letter, contain only lowercase letters, numbers, and hyphens."
    exit 1
fi

log_info "Setting up Nomulus GCP project: $PROJECT_ID"
log_info "Region: $REGION"
log_info "SQL Tier: $SQL_TIER"

# Set the project
log_info "Setting active project to $PROJECT_ID"
gcloud config set project "$PROJECT_ID"

# Get project number
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format="value(projectNumber)")
log_info "Project number: $PROJECT_NUMBER"

# Enable required APIs
log_info "Enabling required Google Cloud APIs..."
APIS=(
    "analyticshub.googleapis.com"
    "appengine.googleapis.com"
    "appenginereporting.googleapis.com"
    "artifactregistry.googleapis.com"
    "autoscaling.googleapis.com"
    "bigquery.googleapis.com"
    "bigqueryconnection.googleapis.com"
    "bigquerydatapolicy.googleapis.com"
    "bigquerymigration.googleapis.com"
    "bigqueryreservation.googleapis.com"
    "bigquerystorage.googleapis.com"
    "cloudapis.googleapis.com"
    "cloudbuild.googleapis.com"
    "cloudkms.googleapis.com"
    "cloudscheduler.googleapis.com"
    "cloudtasks.googleapis.com"
    "cloudtrace.googleapis.com"
    "compute.googleapis.com"
    "container.googleapis.com"
    "containerfilesystem.googleapis.com"
    "containerregistry.googleapis.com"
    "dataform.googleapis.com"
    "dataplex.googleapis.com"
    "datastore.googleapis.com"
    "deploymentmanager.googleapis.com"
    "dns.googleapis.com"
    "gkebackup.googleapis.com"
    "iam.googleapis.com"
    "iamcredentials.googleapis.com"
    "logging.googleapis.com"
    "monitoring.googleapis.com"
    "networkconnectivity.googleapis.com"
    "oslogin.googleapis.com"
    "pubsub.googleapis.com"
    "secretmanager.googleapis.com"
    "servicemanagement.googleapis.com"
    "serviceusage.googleapis.com"
    "sql-component.googleapis.com"
    "sqladmin.googleapis.com"
    "storage-api.googleapis.com"
    "storage-component.googleapis.com"
    "storage.googleapis.com"
    "vpcaccess.googleapis.com"
)

# Enable APIs in batches to avoid rate limits
batch_size=10
for ((i=0; i<${#APIS[@]}; i+=batch_size)); do
    batch=("${APIS[@]:i:batch_size}")
    log_info "Enabling APIs batch $((i/batch_size + 1)): ${batch[*]}"
    gcloud services enable "${batch[@]}" --quiet
done

log_success "All APIs enabled successfully"

# Create custom service accounts
log_info "Creating custom service accounts..."

# Proxy service account
log_info "Creating proxy service account..."
if ! gcloud iam service-accounts describe "proxy-service-account@$PROJECT_ID.iam.gserviceaccount.com" &>/dev/null; then
    gcloud iam service-accounts create proxy-service-account \
        --display-name="Nomulus proxy service account" \
        --description="Service account for Nomulus proxy service"
    log_success "Created proxy service account"
else
    log_warning "Proxy service account already exists"
fi

# SQL proxy service account
log_info "Creating SQL proxy service account..."
if ! gcloud iam service-accounts describe "sql-proxy@$PROJECT_ID.iam.gserviceaccount.com" &>/dev/null; then
    gcloud iam service-accounts create sql-proxy \
        --description="Service account for Cloud SQL proxy"
    log_success "Created SQL proxy service account"
else
    log_warning "SQL proxy service account already exists"
fi

# Assign IAM roles
log_info "Assigning IAM roles..."

# Proxy service account roles
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:proxy-service-account@$PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/logging.logWriter" --quiet

gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:proxy-service-account@$PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/monitoring.metricWriter" --quiet

gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:proxy-service-account@$PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/storage.objectViewer" --quiet

# SQL proxy service account roles
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:sql-proxy@$PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/cloudsql.admin" --quiet

# App Engine default service account roles (will be created automatically when App Engine is initialized)
# These will be applied after App Engine is created

log_success "IAM roles assigned successfully"

# Create Cloud Storage buckets
log_info "Creating Cloud Storage buckets..."

BUCKETS=(
    "$PROJECT_ID-billing"
    "$PROJECT_ID-cert-bucket"
    "$PROJECT_ID-commits"
    "$PROJECT_ID-domain-lists"
    "$PROJECT_ID-gcs-logs"
    "$PROJECT_ID-icann-brda"
    "$PROJECT_ID-icann-zfa"
    "$PROJECT_ID-rde"
    "$PROJECT_ID-reporting"
    "$PROJECT_ID-snapshots"
    "$PROJECT_ID-tfstate"
)

for bucket in "${BUCKETS[@]}"; do
    log_info "Creating bucket: gs://$bucket"
    if ! gsutil ls "gs://$bucket" &>/dev/null; then
        gsutil mb -l "$LOCATION" "gs://$bucket"
        # Enable uniform bucket-level access
        gsutil uniformbucketlevelaccess set on "gs://$bucket"
        log_success "Created bucket: gs://$bucket"
    else
        log_warning "Bucket gs://$bucket already exists"
    fi
done

# Create KMS resources
log_info "Creating KMS key rings and keys..."

# Create nomulus keyring
log_info "Creating nomulus-keyring..."
if ! gcloud kms keyrings describe nomulus-keyring --location=global &>/dev/null; then
    gcloud kms keyrings create nomulus-keyring --location=global
    log_success "Created nomulus-keyring"
else
    log_warning "nomulus-keyring already exists"
fi

# Create nomulus SSL key
log_info "Creating nomulus-ssl-key..."
if ! gcloud kms keys describe nomulus-ssl-key --keyring=nomulus-keyring --location=global &>/dev/null; then
    gcloud kms keys create nomulus-ssl-key \
        --keyring=nomulus-keyring \
        --location=global \
        --purpose=encryption
    log_success "Created nomulus-ssl-key"
else
    log_warning "nomulus-ssl-key already exists"
fi

# Create proxy keyring
log_info "Creating proxy-key-ring..."
if ! gcloud kms keyrings describe proxy-key-ring --location=global &>/dev/null; then
    gcloud kms keyrings create proxy-key-ring --location=global
    log_success "Created proxy-key-ring"
else
    log_warning "proxy-key-ring already exists"
fi

# Create proxy key
log_info "Creating proxy-key..."
if ! gcloud kms keys describe proxy-key --keyring=proxy-key-ring --location=global &>/dev/null; then
    gcloud kms keys create proxy-key \
        --keyring=proxy-key-ring \
        --location=global \
        --purpose=encryption
    log_success "Created proxy-key"
else
    log_warning "proxy-key already exists"
fi

# Create Cloud SQL instance
log_info "Creating Cloud SQL instance..."
if ! gcloud sql instances describe nomulus-db &>/dev/null; then
    log_info "Creating Cloud SQL PostgreSQL instance: nomulus-db"
    log_warning "This may take several minutes..."
    
    gcloud sql instances create nomulus-db \
        --database-version=POSTGRES_17 \
        --tier="$SQL_TIER" \
        --region="$REGION" \
        --storage-type=SSD \
        --storage-size=100GB \
        --storage-auto-increase \
        --backup-start-time=03:00 \
        --maintenance-release-channel=production \
        --maintenance-window-day=SUN \
        --maintenance-window-hour=04 \
        --deletion-protection
    
    log_success "Created Cloud SQL instance: nomulus-db"
    
    # Create application database
    log_info "Creating nomulus-db database..."
    gcloud sql databases create nomulus-db --instance=nomulus-db
    log_success "Created nomulus-db database"
    
else
    log_warning "Cloud SQL instance nomulus-db already exists"
fi

# Initialize App Engine (this will create default buckets and service accounts)
log_info "Initializing App Engine..."
if ! gcloud app describe &>/dev/null; then
    gcloud app create --region="$REGION" --quiet
    log_success "App Engine initialized"
    
    # Now assign roles to App Engine default service account
    log_info "Assigning roles to App Engine default service account..."
    
    APP_ENGINE_SA="$PROJECT_ID@appspot.gserviceaccount.com"
    
    APPENGINE_ROLES=(
        "roles/appengine.deployer"
        "roles/cloudbuild.builds.editor"
        "roles/cloudsql.client"
        "roles/secretmanager.secretAccessor"
        "roles/storage.admin"
        "roles/vpcaccess.user"
    )
    
    for role in "${APPENGINE_ROLES[@]}"; do
        gcloud projects add-iam-policy-binding "$PROJECT_ID" \
            --member="serviceAccount:$APP_ENGINE_SA" \
            --role="$role" --quiet
    done
    
    # Also assign roles to Cloud Build service account
    CLOUDBUILD_SA="$PROJECT_NUMBER@cloudbuild.gserviceaccount.com"
    
    CLOUDBUILD_ROLES=(
        "roles/appengine.deployer"
        "roles/cloudbuild.builds.builder"
        "roles/cloudbuild.builds.editor"
        "roles/storage.admin"
    )
    
    for role in "${CLOUDBUILD_ROLES[@]}"; do
        gcloud projects add-iam-policy-binding "$PROJECT_ID" \
            --member="serviceAccount:$CLOUDBUILD_SA" \
            --role="$role" --quiet
    done
    
    log_success "App Engine service account roles assigned"
else
    log_warning "App Engine already initialized"
fi

# Create staging bucket lifecycle rule
log_info "Configuring staging bucket lifecycle..."
STAGING_BUCKET="staging.$PROJECT_ID.appspot.com"
if gsutil ls "gs://$STAGING_BUCKET" &>/dev/null; then
    # Create lifecycle configuration file
    cat > /tmp/lifecycle.json << EOF
{
  "lifecycle": {
    "rule": [
      {
        "action": {"type": "Delete"},
        "condition": {"age": 30}
      }
    ]
  }
}
EOF
    
    gsutil lifecycle set /tmp/lifecycle.json "gs://$STAGING_BUCKET"
    rm /tmp/lifecycle.json
    log_success "Configured staging bucket lifecycle"
fi

# Summary
log_success "Nomulus GCP project setup completed!"
echo ""
log_info "Summary of created resources:"
echo "  • Project: $PROJECT_ID"
echo "  • Region: $REGION"
echo "  • Service Accounts: 2 custom + default accounts"
echo "  • Storage Buckets: ${#BUCKETS[@]} + App Engine buckets"
echo "  • KMS Key Rings: 2 (with 2 encryption keys)"
echo "  • Cloud SQL: PostgreSQL 17 instance with nomulus-db database"
echo "  • APIs: 43 enabled services"
echo ""
log_info "Next steps:"
echo "  1. Configure DNS settings for your domain"
echo "  2. Set up SSL certificates"
echo "  3. Configure Nomulus application settings"
echo "  4. Deploy the Nomulus application"
echo ""
log_warning "Important: Remember to secure your Cloud SQL instance with appropriate network and user access controls!"