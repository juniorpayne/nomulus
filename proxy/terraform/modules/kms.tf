resource "google_kms_key_ring" "proxy_key_ring" {
  name     = var.proxy_key_ring
  location = var.kms_location
  project  = var.gcp_project_id

  # Key ring lifecycle protection
  lifecycle {
    prevent_destroy = true
  }
}

resource "google_kms_crypto_key" "proxy_key" {
  name     = var.proxy_key
  key_ring = google_kms_key_ring.proxy_key_ring.id # Modern reference instead of self_link
  purpose  = "ENCRYPT_DECRYPT"

  # Enhanced key rotation policy
  rotation_period = var.key_rotation_period

  # Key version template for enhanced security
  version_template {
    algorithm        = "GOOGLE_SYMMETRIC_ENCRYPTION"
    protection_level = var.kms_protection_level
  }

  lifecycle {
    # If a crypto key gets destroyed, all data encrypted with it is lost.
    prevent_destroy = true
  }

  # Labels for better resource management
  labels = {
    environment = var.environment
    component   = "proxy"
    purpose     = "ssl-certificate"
    managed-by  = "terraform"
  }
}

# Backup encryption key for disaster recovery
resource "google_kms_crypto_key" "proxy_backup_key" {
  count    = var.enable_backup_encryption ? 1 : 0
  name     = "${var.proxy_key}-backup"
  key_ring = google_kms_key_ring.proxy_key_ring.id
  purpose  = "ENCRYPT_DECRYPT"

  # Different rotation schedule for backup key
  rotation_period = var.backup_key_rotation_period

  version_template {
    algorithm        = "GOOGLE_SYMMETRIC_ENCRYPTION"
    protection_level = var.kms_protection_level
  }

  lifecycle {
    prevent_destroy = true
  }

  labels = {
    environment = var.environment
    component   = "proxy"
    purpose     = "backup-encryption"
    managed-by  = "terraform"
  }
}

# Modern IAM binding with enhanced security
resource "google_kms_crypto_key_iam_member" "ssl_key_decrypter" {
  crypto_key_id = google_kms_crypto_key.proxy_key.id # Modern reference
  role          = "roles/cloudkms.cryptoKeyDecrypter"
  member        = "serviceAccount:${google_service_account.proxy_service_account.email}"

  # IAM condition for enhanced security
  condition {
    title       = "Proxy service decryption access"
    description = "Allow decryption only from proxy service context"
    expression  = "has(request.auth.access_levels)"
  }
}

resource "google_kms_crypto_key_iam_member" "ssl_key_encrypter" {
  crypto_key_id = google_kms_crypto_key.proxy_key.id
  role          = "roles/cloudkms.cryptoKeyEncrypter"
  member        = "serviceAccount:${google_service_account.proxy_service_account.email}"
}

# Backup key IAM permissions
resource "google_kms_crypto_key_iam_member" "backup_key_decrypter" {
  count         = var.enable_backup_encryption ? 1 : 0
  crypto_key_id = google_kms_crypto_key.proxy_backup_key[0].id
  role          = "roles/cloudkms.cryptoKeyDecrypter"
  member        = "serviceAccount:${google_service_account.proxy_service_account.email}"
}

# Key ring viewer permission for monitoring
resource "google_kms_key_ring_iam_member" "key_ring_viewer" {
  key_ring_id = google_kms_key_ring.proxy_key_ring.id
  role        = "roles/cloudkms.viewer"
  member      = "serviceAccount:${google_service_account.proxy_service_account.email}"
}
