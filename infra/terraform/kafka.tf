resource "docker_image" "kafka" {
  name         = "apache/kafka:4.3.2"
  keep_locally = true
}

resource "docker_container" "kafka" {
  name         = "aether-terraform-kafka"
  hostname     = "kafka"
  image        = docker_image.kafka.image_id
  log_driver   = "json-file"
  log_opts     = local.log_opts
  wait         = true
  wait_timeout = 240

  env = [
    "KAFKA_NODE_ID=1",
    "KAFKA_PROCESS_ROLES=broker,controller",
    "KAFKA_LISTENERS=CONTROLLER://:9093,INTERNAL://:9092,EXTERNAL://:19092",
    "KAFKA_ADVERTISED_LISTENERS=INTERNAL://kafka:9092,EXTERNAL://localhost:${var.host_ports.kafka}",
    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT",
    "KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER",
    "KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093",
    "KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL",
    "CLUSTER_ID=4L6g3nShT-eMCtK--X86sw",
    "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1",
    "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1",
    "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1",
    "KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR=1",
    "KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR=1",
    "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0",
    "KAFKA_NUM_PARTITIONS=6",
    "KAFKA_AUTO_CREATE_TOPICS_ENABLE=false",
    "KAFKA_LOG_DIRS=/var/lib/kafka/data",
  ]

  ports {
    internal = 19092
    external = var.host_ports.kafka
    ip       = "127.0.0.1"
  }
  networks_advanced {
    name    = docker_network.aether.name
    aliases = ["kafka"]
  }
  volumes {
    volume_name    = docker_volume.kafka.name
    container_path = "/var/lib/kafka/data"
  }
  healthcheck {
    test         = ["CMD", "/opt/kafka/bin/kafka-broker-api-versions.sh", "--bootstrap-server", "localhost:9092"]
    interval     = "10s"
    timeout      = "20s"
    retries      = 12
    start_period = "20s"
  }
}

resource "docker_container" "kafka_init" {
  name       = "aether-terraform-kafka-init"
  image      = docker_image.kafka.image_id
  entrypoint = ["/opt/kafka/bin/kafka-topics.sh"]
  command = [
    "--bootstrap-server", "kafka:9092", "--create", "--if-not-exists",
    "--topic", "truck-events", "--partitions", "6", "--replication-factor", "1",
  ]
  must_run   = false
  attach     = true
  logs       = true
  log_driver = "json-file"
  log_opts   = local.log_opts
  depends_on = [docker_container.kafka]

  networks_advanced {
    name = docker_network.aether.name
  }
  lifecycle {
    postcondition {
      condition     = self.exit_code == 0
      error_message = "Kafka topic creation failed; inspect aether-terraform-kafka-init logs."
    }
  }
}
