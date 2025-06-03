output "proxy_name_servers" {
  value = google_dns_managed_zone.proxy_domain.name_servers
}

output "proxy_instance_groups" {
  value = local.proxy_instance_groups
}

output "proxy_service_account" {
  description = "Proxy service account information"
  value = {
    email     = google_service_account.proxy_service_account.email
    client_id = google_service_account.proxy_service_account.unique_id
    name      = google_service_account.proxy_service_account.name
  }
}

output "kms_key_info" {
  description = "KMS key information for encryption"
  value = {
    key_ring_id   = google_kms_key_ring.proxy_key_ring.id
    crypto_key_id = google_kms_crypto_key.proxy_key.id
    backup_key_id = var.enable_backup_encryption ? google_kms_crypto_key.proxy_backup_key[0].id : null
  }
  sensitive = true
}

output "storage_buckets" {
  description = "Storage bucket information"
  value = {
    certificate_bucket = google_storage_bucket.proxy_certificate.name
    certificate_url    = google_storage_bucket.proxy_certificate.url
    backup_bucket      = var.enable_backup_bucket ? google_storage_bucket.proxy_backup[0].name : null
    backup_url         = var.enable_backup_bucket ? google_storage_bucket.proxy_backup[0].url : null
  }
}

output "workload_identity_config" {
  description = "Workload Identity configuration for GKE"
  value = {
    service_account_email = google_service_account.proxy_service_account.email
    kubernetes_namespace  = var.kubernetes_namespace
    kubernetes_sa_name    = var.kubernetes_service_account
    workload_pool         = "${var.gcp_project_id}.svc.id.goog"
  }
}

output "proxy_ip_addresses" {
  value = {
    ipv4        = module.proxy_networking.proxy_ipv4_address
    ipv6        = module.proxy_networking.proxy_ipv6_address
    ipv4_canary = module.proxy_networking_canary.proxy_ipv4_address
    ipv6_canary = module.proxy_networking_canary.proxy_ipv6_address
  }
}
