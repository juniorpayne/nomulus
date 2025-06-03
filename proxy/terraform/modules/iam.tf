resource "google_service_account" "proxy_service_account" {
  account_id   = "proxy-service-account"
  display_name = "Nomulus proxy service account"
  description  = "Service account for Nomulus proxy operations with least-privilege access"
  
  # Enhanced security settings
  disabled = false
  
  # Automatic key rotation (when using workload identity)
  lifecycle {
    prevent_destroy = true
  }
}

# Modern IAM binding with conditions and enhanced security
resource "google_project_iam_member" "metric_writer" {
  project = var.gcp_project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${google_service_account.proxy_service_account.email}"
  
  # Optional condition for environment-specific access
  dynamic "condition" {
    for_each = var.environment != "production" ? [1] : []
    content {
      title       = "Development environment metric access"
      description = "Conditional access for non-production environments"
      expression  = "request.time.getHours() >= 6 && request.time.getHours() <= 22"
    }
  }
}

resource "google_project_iam_member" "log_writer" {
  project = var.gcp_project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.proxy_service_account.email}"
}

# Additional enhanced security roles for modern GKE workloads
resource "google_project_iam_member" "workload_identity_user" {
  project = var.gcp_project_id
  role    = "roles/iam.workloadIdentityUser"
  member  = "serviceAccount:${google_service_account.proxy_service_account.email}"
}

resource "google_project_iam_member" "artifact_registry_reader" {
  count   = var.enable_artifact_registry ? 1 : 0
  project = var.gcp_project_id
  role    = "roles/artifactregistry.reader"
  member  = "serviceAccount:${google_service_account.proxy_service_account.email}"
}

# Service account key management for legacy compatibility
resource "google_service_account_key" "proxy_service_key" {
  count              = var.create_service_account_key ? 1 : 0
  service_account_id = google_service_account.proxy_service_account.name
  
  # Key rotation lifecycle
  lifecycle {
    create_before_destroy = true
  }
}

# Workload Identity binding for GKE pods
resource "google_service_account_iam_member" "workload_identity_binding" {
  service_account_id = google_service_account.proxy_service_account.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.gcp_project_id}.svc.id.goog[${var.kubernetes_namespace}/${var.kubernetes_service_account}]"
}
