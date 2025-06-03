module "proxy_gke_americas" {
  source                      = "./gke"
  proxy_cluster_region        = "americas"
  proxy_service_account_email = google_service_account.proxy_service_account.email
  gcp_project_id              = var.gcp_project_id
  environment                 = var.environment
}

module "proxy_gke_emea" {
  source                      = "./gke"
  proxy_cluster_region        = "emea"
  proxy_service_account_email = google_service_account.proxy_service_account.email
  gcp_project_id              = var.gcp_project_id
  environment                 = var.environment
}

module "proxy_gke_apac" {
  source                      = "./gke"
  proxy_cluster_region        = "apac"
  proxy_service_account_email = google_service_account.proxy_service_account.email
  gcp_project_id              = var.gcp_project_id
  environment                 = var.environment
}

locals {
  proxy_instance_groups = {
    americas = module.proxy_gke_americas.proxy_instance_group
    emea     = module.proxy_gke_emea.proxy_instance_group
    apac     = module.proxy_gke_apac.proxy_instance_group
  }
}
