resource "docker_image" "postgres" {
  name         = "postgres:17-alpine"
  keep_locally = true
}

resource "docker_container" "postgres" {
  name         = "aether-terraform-postgres"
  image        = docker_image.postgres.image_id
  env          = ["POSTGRES_DB=aether", "POSTGRES_USER=aether", "POSTGRES_PASSWORD=${var.pg_password}"]
  log_driver   = "json-file"
  log_opts     = local.log_opts
  wait         = true
  wait_timeout = 180

  upload {
    file    = "/docker-entrypoint-initdb.d/01-schema.sql"
    content = file("${path.module}/../db/schema.sql")
  }
  volumes {
    volume_name    = docker_volume.postgres.name
    container_path = "/var/lib/postgresql/data"
  }
  ports {
    internal = 5432
    external = var.host_ports.postgres
    ip       = "127.0.0.1"
  }
  networks_advanced {
    name    = docker_network.aether.name
    aliases = ["postgres"]
  }
  healthcheck {
    # The temporary init server uses a Unix socket; TCP waits for schema loading.
    test         = ["CMD", "pg_isready", "-h", "127.0.0.1", "-U", "aether", "-d", "aether"]
    interval     = "5s"
    timeout      = "5s"
    retries      = 20
    start_period = "5s"
  }
}
