variable "proxy_project_name" {
  description = "GCP project in which the proxy runs."
}

variable "gcr_project_name" {
  description = "GCP project from which the proxy image is pulled."
}

variable "proxy_domain_name" {
  description = <<EOF
    The base domain name of the proxy, without the whois. or epp. part.
    EOF
}

variable "proxy_certificate_bucket" {
  description = <<EOF
    The GCS bucket that stores the encrypted SSL certificate.  The "gs://"
    prefix should be omitted.
    EOF
}

variable "proxy_key_ring" {
  default     = "proxy-key-ring"
  description = "Cloud KMS keyring name"
}

variable "proxy_key" {
  default     = "proxy-key"
  description = "Cloud KMS key name"
}

variable "proxy_ports" {
  type        = map(number)
  description = "Node ports exposed by the proxy."

  default = {
    health_check = 30000
    whois        = 30001
    epp          = 30002
    http-whois   = 30010
    https-whois  = 30011
  }
}

variable "proxy_ports_canary" {
  type        = map(number)
  description = "Node ports exposed by the canary proxy."

  default = {
    health_check = 31000
    whois        = 31001
    epp          = 31002
    http-whois   = 31010
    https-whois  = 31011
  }
}

variable "public_web_whois" {
  type        = number
  default     = 1
  description = <<EOF
    Set to 1 if the whois HTTP ports are external, 0 if not.  This is necessary
    because our test projects are configured with
    constraints/compute.restrictLoadBalancerCreationForTypes, which prohibits
    forwarding external HTTP(s) connections.
    EOF
}

# Enhanced security and modernization variables
variable "gcp_project_id" {
  description = "GCP Project ID for resource management and IAM"
  type        = string
}

variable "environment" {
  description = "Environment name (production, staging, development)"
  type        = string
  default     = "production"
}

variable "kms_location" {
  description = "Location for KMS key ring (global, regional)"
  type        = string
  default     = "global"
}

variable "key_rotation_period" {
  description = "Key rotation period in seconds (default: 90 days)"
  type        = string
  default     = "7776000s"  # 90 days
}

variable "backup_key_rotation_period" {
  description = "Backup key rotation period in seconds (default: 180 days)"
  type        = string
  default     = "15552000s"  # 180 days
}

variable "kms_protection_level" {
  description = "Protection level for KMS keys (SOFTWARE, HSM)"
  type        = string
  default     = "SOFTWARE"
}

variable "enable_backup_encryption" {
  description = "Enable separate backup encryption key"
  type        = bool
  default     = false
}

variable "enable_artifact_registry" {
  description = "Enable Artifact Registry access for service account"
  type        = bool
  default     = false
}

variable "create_service_account_key" {
  description = "Create service account key for legacy compatibility"
  type        = bool
  default     = false
}

variable "kubernetes_namespace" {
  description = "Kubernetes namespace for Workload Identity binding"
  type        = string
  default     = "default"
}

variable "kubernetes_service_account" {
  description = "Kubernetes service account for Workload Identity binding"
  type        = string
  default     = "proxy-service-account"
}

variable "gcs_location" {
  description = "Location for GCS buckets"
  type        = string
  default     = "US"
}

variable "backup_gcs_location" {
  description = "Location for backup GCS bucket (should be different region)"
  type        = string
  default     = "EU"
}

variable "gcs_logging_bucket" {
  description = "GCS bucket for access logging"
  type        = string
  default     = ""
}

variable "enable_backup_bucket" {
  description = "Enable backup GCS bucket for disaster recovery"
  type        = bool
  default     = false
}
