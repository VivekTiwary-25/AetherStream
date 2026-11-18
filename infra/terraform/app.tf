resource "docker_image" "app" {
  for_each = toset(["engine", "simulator", "control-api", "web"])
  name     = "${var.registry_prefix}${each.key}:${var.image_tag}"
  # The provider uses an existing local tag before attempting a pull.
  # CI builds all four tags first; keep them on destroy as well.
  keep_locally = true
}

locals {
  app_env = [
    "KAFKA_BOOTSTRAP=kafka:9092",
    "KAFKA_TOPIC=truck-events",
    "PG_URL=jdbc:postgresql://postgres:5432/aether",
    "PG_USER=aether",
    "PG_PASSWORD=${var.pg_password}",
    "S3_ENDPOINT=http://minio:9000",
    "S3_ACCESS_KEY=aether",
    "S3_SECRET_KEY=aether-secret",
    "S3_BUCKET=aether-checkpoints",
    "COORDINATOR_URL=http://coordinator:8081",
    "SIMULATOR_URL=http://simulator:8070",
    "GIT_SHA=${var.image_tag}",
  ]
}

resource "docker_container" "coordinator" {
  name                  = "aether-terraform-coordinator"
  image                 = docker_image.app["engine"].image_id
  env                   = concat(local.app_env, ["CHECKPOINT_INTERVAL_MS=10000"])
  restart               = "unless-stopped"
  destroy_grace_seconds = 5
  log_driver            = "json-file"
  log_opts              = local.log_opts
  wait                  = true
  wait_timeout          = 240
  depends_on            = [docker_container.kafka_init, docker_container.minio_init, docker_container.postgres]

  ports {
    internal = 8081
    external = var.host_ports.coordinator
    ip       = "127.0.0.1"
  }
  networks_advanced {
    name    = docker_network.aether.name
    aliases = ["coordinator"]
  }
  healthcheck {
    test         = ["CMD", "curl", "-fsS", "--max-time", "3", "http://localhost:8081/coord/health"]
    interval     = "5s"
    timeout      = "5s"
    retries      = 30
    start_period = "20s"
  }
}

resource "docker_container" "worker" {
  count                 = 3
  name                  = "aether-terraform-worker-${count.index + 1}"
  image                 = docker_image.app["engine"].image_id
  entrypoint            = ["/app/bin/worker"]
  env                   = concat(local.app_env, ["WORKER_ID=worker-${count.index + 1}"])
  restart               = "unless-stopped"
  destroy_grace_seconds = 5
  log_driver            = "json-file"
  log_opts              = local.log_opts
  wait                  = true
  wait_timeout          = 180
  depends_on            = [docker_container.coordinator]

  networks_advanced {
    name    = docker_network.aether.name
    aliases = ["worker-${count.index + 1}"]
  }
  healthcheck {
    test         = ["CMD", "curl", "-fsS", "--max-time", "3", "http://localhost:8090/health"]
    interval     = "5s"
    timeout      = "5s"
    retries      = 30
    start_period = "10s"
  }
}

resource "docker_container" "simulator" {
  name         = "aether-terraform-simulator"
  image        = docker_image.app["simulator"].image_id
  env          = concat(local.app_env, ["SIM_SEED=42", "SIM_TIME_SCALE=10"])
  log_driver   = "json-file"
  log_opts     = local.log_opts
  wait         = true
  wait_timeout = 180
  depends_on   = [docker_container.kafka_init]

  ports {
    internal = 8070
    external = var.host_ports.simulator
    ip       = "127.0.0.1"
  }
  networks_advanced {
    name    = docker_network.aether.name
    aliases = ["simulator"]
  }
  healthcheck {
    test         = ["CMD", "curl", "-fsS", "--max-time", "3", "http://localhost:8070/health"]
    interval     = "5s"
    timeout      = "5s"
    retries      = 30
    start_period = "10s"
  }
}

resource "docker_container" "control_api" {
  name         = "aether-terraform-control-api"
  image        = docker_image.app["control-api"].image_id
  env          = local.app_env
  log_driver   = "json-file"
  log_opts     = local.log_opts
  wait         = true
  wait_timeout = 240
  depends_on   = [docker_container.coordinator, docker_container.simulator]

  ports {
    internal = 8080
    external = var.host_ports.control_api
    ip       = "127.0.0.1"
  }
  networks_advanced {
    name    = docker_network.aether.name
    aliases = ["control-api"]
  }
  healthcheck {
    test         = ["CMD", "curl", "-fsS", "--max-time", "3", "http://localhost:8080/api/ready"]
    interval     = "5s"
    timeout      = "5s"
    retries      = 30
    start_period = "30s"
  }
}

resource "docker_container" "web" {
  name         = "aether-terraform-web"
  image        = docker_image.app["web"].image_id
  log_driver   = "json-file"
  log_opts     = local.log_opts
  wait         = true
  wait_timeout = 180
  depends_on   = [docker_container.control_api]

  ports {
    internal = 80
    external = var.host_ports.web
    ip       = "127.0.0.1"
  }
  networks_advanced {
    name    = docker_network.aether.name
    aliases = ["web"]
  }
  healthcheck {
    test         = ["CMD", "wget", "-q", "-O", "/dev/null", "-T", "3", "http://127.0.0.1/"]
    interval     = "5s"
    timeout      = "5s"
    retries      = 30
    start_period = "5s"
  }
}
