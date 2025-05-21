#resource "google_compute_ssl_certificate" "alpha_proxy_cert" {
#  name        = "alpha-proxy-cert"
#  private_key = file("${path.root}/certs/ssl-cert-key.pem")
#  certificate = file("${path.root}/certs/ssl-cert-key.pem")
#}
#
## List of instance‑group self_links produced by the module
#locals {
#  igs = values(module.proxy.proxy_instance_groups)
#}
#
## Re‑wire epp-backend-service: KEEP the backends, just swap health_check
#resource "google_compute_backend_service" "epp_backend_service_patch" {
#  name            = "epp-backend-service"
#  health_checks   = [google_compute_health_check.tcp_700.id]
#
#  dynamic "backend" {
#    for_each = local.igs
#    content {
#      group = backend.value       # self_link of the IG
#    }
#  }
#
#  lifecycle {
#    # Leave everything else alone on future runs
#    ignore_changes = [timeout_sec, connection_draining_timeout_sec]
#  }
#}
#
#resource "google_compute_target_tcp_proxy" "epp_tcp_proxy_cert_patch" {
#  name            = "epp-tcp-proxy"
#  backend_service = "epp-backend-service"
#  proxy_header    = "NONE"
#  #ssl_certificates = [google_compute_ssl_certificate.alpha_proxy_cert.self_link]
#
#  lifecycle { ignore_changes = [backend_service] }
#}
#
