variable "proxy_service_account_email" {
  description = "Email address of the service account for proxy cluster nodes"
  type        = string
}

variable "proxy_cluster_region" {
  description = "Region for the proxy cluster deployment"
  type        = string
}

variable "proxy_cluster_zones" {
  description = "Map of regions to zones for proxy cluster deployment"
  type        = map(string)

  default = {
    americas = "us-east4-a"
    emea     = "europe-west4-b"
    apac     = "asia-northeast1-c"
  }
}

variable "gcp_project_id" {
  description = "GCP Project ID for Workload Identity configuration"
  type        = string
}

variable "environment" {
  description = "Environment name (production, staging, development)"
  type        = string
  default     = "production"
}
