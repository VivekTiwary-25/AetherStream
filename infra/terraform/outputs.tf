output "urls" {
  description = "Local demo endpoints (all published ports bind to loopback)."
  value = {
    web           = "http://localhost:${var.host_ports.web}"
    control_api   = "http://localhost:${var.host_ports.control_api}/api"
    readiness     = "http://localhost:${var.host_ports.control_api}/api/ready"
    coordinator   = "http://localhost:${var.host_ports.coordinator}/coord/health"
    simulator     = "http://localhost:${var.host_ports.simulator}/state"
    minio_api     = "http://localhost:${var.host_ports.minio_api}"
    minio_console = "http://localhost:${var.host_ports.minio_console}"
    kafka         = "localhost:${var.host_ports.kafka}"
    postgres      = "postgresql://aether@localhost:${var.host_ports.postgres}/aether"
  }
}
