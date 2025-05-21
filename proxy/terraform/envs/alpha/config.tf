###############################################################################

terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.33"
    }
  }

  backend "gcs" {
    bucket = "example-registry-alpha-tfstate"
    prefix = "terraform/state/alpha"
  }
}

###############################################################################
# Google provider (for any top‑level resources you may add later)
###############################################################################
provider "google" {
  project = "example-registry-alpha"
  region  = "us-central1"
}

###############################################################################
# Nomulus proxy module – creates GKE clusters, global TCP LB, DNS, etc.
###############################################################################
module "proxy" {
  source = "../../modules"

  # Projects
  proxy_project_name = "example-registry-alpha"
  gcr_project_name   = "example-registry-alpha"

  # Public FQDN base  ►  whois.<domain> / epp.<domain>
  proxy_domain_name = "example-registry-alpha.thepaynes.ca"

  # Where an encrypted cert could live (module will ignore if unused)
  proxy_certificate_bucket = "example-registry-alpha-cert-bucket"

  # Disable the HTTP/HTTPS WHOIS endpoints (keep only TCP 43 / 700)
  public_web_whois = 0

  bucket_uniform_access              = true
  proxy_certificate_bucket_location  = "US"
}

locals { igs = values(module.proxy.proxy_instance_groups) }

