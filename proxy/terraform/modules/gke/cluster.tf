locals {
  proxy_cluster_zone = lookup(var.proxy_cluster_zones, var.proxy_cluster_region)
}

resource "google_container_cluster" "proxy_cluster" {
  name     = "proxy-cluster-${var.proxy_cluster_region}"
  location = local.proxy_cluster_zone

  # Modern GKE configuration for enhanced security
  deletion_protection = false
  
  # Enable Workload Identity for enhanced security
  workload_identity_config {
    workload_pool = "${var.gcp_project_id}.svc.id.goog"
  }

  # Security configuration
  cluster_security_group = "gke-security-groups"
  
  # Network configuration for improved security
  network_policy {
    enabled = true
  }

  # Enable shielded nodes for security
  node_config {
    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }
  }

  # Logging and monitoring configuration
  logging_config {
    enable_components = ["SYSTEM_COMPONENTS", "WORKLOADS"]
  }

  monitoring_config {
    enable_components = ["SYSTEM_COMPONENTS"]
    
    managed_prometheus {
      enabled = true
    }
  }

  # Binary authorization for enhanced security
  binary_authorization {
    evaluation_mode = "PROJECT_SINGLETON_POLICY_ENFORCE"
  }

  timeouts {
    create = "30m"
    update = "30m"
    delete = "30m"
  }

  node_pool {
    name               = "proxy-node-pool"
    initial_node_count = 1

    node_config {
      tags = [
        "proxy-cluster",
        "gke-node",
      ]

      service_account = var.proxy_service_account_email
      
      # Modern machine configuration
      machine_type = "e2-standard-2"
      disk_size_gb = 50
      disk_type    = "pd-ssd"
      image_type   = "COS_CONTAINERD"

      # Enhanced security configuration
      shielded_instance_config {
        enable_secure_boot          = true
        enable_integrity_monitoring = true
      }

      # Workload Identity support
      workload_metadata_config {
        mode = "GKE_METADATA"
      }

      oauth_scopes = [
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
      ]

      # Labels for resource management
      labels = {
        environment = var.environment
        component   = "proxy"
        managed-by  = "terraform"
      }

      # Taints for dedicated workloads
      taint {
        key    = "proxy-workload"
        value  = "true"
        effect = "NO_SCHEDULE"
      }
    }

    autoscaling {
      max_node_count = 15
      min_node_count = 1
    }

    management {
      auto_repair  = true
      auto_upgrade = true
    }

    # Modern upgrade settings
    upgrade_settings {
      strategy        = "SURGE"
      max_surge       = 1
      max_unavailable = 0
    }
  }
}
