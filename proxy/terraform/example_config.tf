terraform {
  required_version = ">= 1.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.37.0"
    }
  }

  backend "gcs" {
    # The name of the GCS bucket that stores the terraform.tfstate file.
    bucket = "YOUR_GCS_BUCKET"
    prefix = "terraform/state"
  }
}

module "proxy" {
  source                   = "./modules"
  proxy_project_name       = "YOUR_PROXY_PROJECT"
  gcr_project_name         = "YOUR_GCR_PROJECT"
  proxy_domain_name        = "YOUR_PROXY_DOMAIN"
  proxy_certificate_bucket = "YOUR_CERTIFICATE_BUCKET"
  gcp_project_id           = "YOUR_GCP_PROJECT_ID"

  # Uncomment to disable forwarding of whois HTTP interfaces.
  # public_web_whois         = 0
}
