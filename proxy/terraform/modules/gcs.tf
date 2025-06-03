resource "google_storage_bucket" "proxy_certificate" {
  name                        = var.proxy_certificate_bucket
  location                    = var.gcs_location
  storage_class               = "STANDARD"
  project                     = var.gcp_project_id
  uniform_bucket_level_access = true # Enhanced security

  # Enhanced security configuration
  public_access_prevention = "enforced"

  # Encryption configuration
  encryption {
    default_kms_key_name = google_kms_crypto_key.proxy_key.id
  }

  # Versioning for data protection
  versioning {
    enabled = true
  }

  # Lifecycle rules for cost optimization
  lifecycle_rule {
    condition {
      age = 90
    }
    action {
      type = "Delete"
    }
  }

  lifecycle_rule {
    condition {
      age = 30
    }
    action {
      type          = "SetStorageClass"
      storage_class = "NEARLINE"
    }
  }

  # Logging configuration
  logging {
    log_bucket        = var.gcs_logging_bucket
    log_object_prefix = "proxy-certificate-access-"
  }

  # Labels for resource management
  labels = {
    environment = var.environment
    component   = "proxy"
    purpose     = "ssl-certificates"
    managed-by  = "terraform"
  }

  # Prevent accidental deletion
  lifecycle {
    prevent_destroy = true
  }
}

# Modern IAM binding with enhanced security
resource "google_storage_bucket_iam_member" "certificate_viewer" {
  bucket = google_storage_bucket.proxy_certificate.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.proxy_service_account.email}"

  # IAM condition for time-based access
  condition {
    title       = "Certificate access during business hours"
    description = "Allow certificate access only during business hours for non-production"
    expression  = var.environment == "production" ? "true" : "request.time.getHours() >= 6 && request.time.getHours() <= 22"
  }
}

# Container Registry access with modern IAM
resource "google_storage_bucket_iam_member" "gcr_viewer" {
  bucket = "artifacts.${var.gcr_project_name}.appspot.com"
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.proxy_service_account.email}"
}

# Backup bucket for disaster recovery
resource "google_storage_bucket" "proxy_backup" {
  count                       = var.enable_backup_bucket ? 1 : 0
  name                        = "${var.proxy_certificate_bucket}-backup"
  location                    = var.backup_gcs_location
  storage_class               = "COLDLINE"
  project                     = var.gcp_project_id
  uniform_bucket_level_access = true

  public_access_prevention = "enforced"

  # Cross-region backup encryption
  encryption {
    default_kms_key_name = var.enable_backup_encryption ? google_kms_crypto_key.proxy_backup_key[0].id : google_kms_crypto_key.proxy_key.id
  }

  versioning {
    enabled = true
  }

  # Extended retention for backup
  lifecycle_rule {
    condition {
      age = 365
    }
    action {
      type = "Delete"
    }
  }

  labels = {
    environment = var.environment
    component   = "proxy"
    purpose     = "backup-certificates"
    managed-by  = "terraform"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_storage_bucket_iam_member" "backup_viewer" {
  count  = var.enable_backup_bucket ? 1 : 0
  bucket = google_storage_bucket.proxy_backup[0].name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.proxy_service_account.email}"
}
