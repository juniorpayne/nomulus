########################################
# Service‑account & DNS information
########################################
output "proxy_service_account" {
  description = "Service account the proxy runs under."
  value       = module.proxy.proxy_service_account
}

output "proxy_name_servers" {
  description = "Authoritative name‑servers for the proxy’s delegated DNS zone."
  value       = module.proxy.proxy_name_servers
}

########################################
# Global load‑balancer addresses
# (single address handles BOTH EPP :700 and WHOIS :43)
########################################
output "proxy_ipv4" {
  description = "Global IPv4 used by the EPP/WHOIS TCP proxy"
  value       = module.proxy.proxy_ip_addresses["ipv4"]
}

output "proxy_ipv6" {
  description = "Global IPv6 used by the EPP/WHOIS TCP proxy"
  value       = module.proxy.proxy_ip_addresses["ipv6"]
}


