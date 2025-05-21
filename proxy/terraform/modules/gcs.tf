resource "google_storage_bucket" "proxy_certificate" {
  name          = var.proxy_certificate_bucket
  location      = var.proxy_certificate_bucket_location
  storage_class = "STANDARD"
}

resource "google_storage_bucket_iam_member" "certificate_viewer" {
  bucket = google_storage_bucket.proxy_certificate.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.proxy_service_account.email}"
}

resource "google_storage_bucket_iam_member" "gcr_viewer" {
  bucket = "artifacts.${var.gcr_project_name}.appspot.com"
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.proxy_service_account.email}"
}
