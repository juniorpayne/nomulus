# Main configuration for the Nomulus proxy modules
terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.37.0"
    }
  }
}