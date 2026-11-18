resource "docker_image" "minio" {
  name         = "pgsty/minio:RELEASE.2026-08-04T00-00-00Z"
  keep_locally = true
}

resource "docker_image" "mc" {
  name         = "pgsty/mc:RELEASE.2026-11-12T00-00-00Z"
  keep_locally = true
}

resource "docker_container" "minio" {
  name         = "aether-terraform-minio"
  image        = docker_image.minio.image_id
  command      = ["server", "/data", "--console-address", ":9001"]
  env          = ["MINIO_ROOT_USER=aether", "MINIO_ROOT_PASSWORD=aether-secret"]
  log_driver   = "json-file"
  log_opts     = local.log_opts
  wait         = true
  wait_timeout = 180

  ports {
    internal = 9000
    external = var.host_ports.minio_api
    ip       = "127.0.0.1"
  }
  ports {
    internal = 9001
    external = var.host_ports.minio_console
    ip       = "127.0.0.1"
  }
  volumes {
    volume_name    = docker_volume.minio.name
    container_path = "/data"
  }
  networks_advanced {
    name    = docker_network.aether.name
    aliases = ["minio"]
  }
  healthcheck {
    test         = ["CMD", "/usr/bin/curl", "-f", "http://127.0.0.1:9000/minio/health/ready"]
    interval     = "5s"
    timeout      = "5s"
    retries      = 20
    start_period = "5s"
  }
}

resource "docker_container" "minio_init" {
  name       = "aether-terraform-minio-init"
  image      = docker_image.mc.image_id
  env        = ["MC_HOST_local=http://aether:aether-secret@minio:9000"]
  command    = ["mb", "--ignore-existing", "local/aether-checkpoints"]
  must_run   = false
  attach     = true
  logs       = true
  log_driver = "json-file"
  log_opts   = local.log_opts
  depends_on = [docker_container.minio]

  networks_advanced {
    name = docker_network.aether.name
  }
  lifecycle {
    postcondition {
      condition     = self.exit_code == 0
      error_message = "Checkpoint bucket creation failed; inspect aether-terraform-minio-init logs."
    }
  }
}
