output "proxy_ipv4_address" {
  description = "The IPv4 address of the global load balancer"
  value       = google_compute_global_address.proxy_ipv4_address.address
}

output "proxy_ipv6_address" {
  description = "The IPv6 address of the global load balancer"
  value       = google_compute_global_address.proxy_ipv6_address.address
}

output "epp_endpoint_ipv4" {
  description = "EPP endpoint IPv4 DNS name"
  value       = google_dns_record_set.proxy_epp_a_record.name
}

output "epp_endpoint_ipv6" {
  description = "EPP endpoint IPv6 DNS name"
  value       = google_dns_record_set.proxy_epp_aaaa_record.name
}

output "whois_endpoint_ipv4" {
  description = "WHOIS endpoint IPv4 DNS name"
  value       = google_dns_record_set.proxy_whois_a_record.name
}

output "whois_endpoint_ipv6" {
  description = "WHOIS endpoint IPv6 DNS name"
  value       = google_dns_record_set.proxy_whois_aaaa_record.name
}

output "backend_services" {
  description = "Map of backend service self links"
  value = {
    epp         = google_compute_backend_service.epp_backend_service.self_link
    whois       = google_compute_backend_service.whois_backend_service.self_link
    https_whois = google_compute_backend_service.https_whois_backend_service.self_link
    http_whois  = google_compute_backend_service.http_whois_backend_service.self_link
  }
}

output "health_checks" {
  description = "Map of health check self links"
  value = {
    tcp  = google_compute_health_check.proxy_health_check.self_link
    http = google_compute_health_check.proxy_http_health_check.self_link
  }
}
