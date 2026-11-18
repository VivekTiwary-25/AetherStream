resource "docker_volume" "kafka" {
  name = "aether-terraform-kafka-data"
}

resource "docker_volume" "postgres" {
  name = "aether-terraform-postgres-data"
}

resource "docker_volume" "minio" {
  name = "aether-terraform-minio-data"
}
