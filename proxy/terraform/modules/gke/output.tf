output "cluster_name" {
  description = "The name of the GKE cluster"
  value       = google_container_cluster.proxy_cluster.name
}

output "cluster_location" {
  description = "The location (zone) of the GKE cluster"
  value       = google_container_cluster.proxy_cluster.location
}

output "cluster_endpoint" {
  description = "The IP address of the GKE cluster master"
  value       = google_container_cluster.proxy_cluster.endpoint
  sensitive   = true
}

output "cluster_ca_certificate" {
  description = "The cluster CA certificate for kubectl configuration"
  value       = google_container_cluster.proxy_cluster.master_auth[0].cluster_ca_certificate
  sensitive   = true
}

output "workload_identity_pool" {
  description = "The Workload Identity pool for service account bindings"
  value       = google_container_cluster.proxy_cluster.workload_identity_config[0].workload_pool
}