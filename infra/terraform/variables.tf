variable "image_tag" {
  description = "Published commit SHA shared by all four app images, or ci for locally built CI images."
  type        = string

  validation {
    condition     = can(regex("^([0-9a-f]{40}|ci)$", var.image_tag))
    error_message = "Use a full lowercase commit SHA, or ci for locally built images."
  }
}

variable "registry_prefix" {
  description = "Image name prefix; engine, simulator, control-api and web are appended."
  type        = string
  default     = "ghcr.io/vivektiwary-25/aetherstream-"
}

variable "pg_password" {
  description = "Demo database password. Sensitive values are still stored in Terraform state."
  type        = string
  sensitive   = true
  default     = "aether"
}

variable "host_ports" {
  description = "Loopback-only host ports. Change these if Compose is already running."
  type = object({
    kafka         = number
    postgres      = number
    minio_api     = number
    minio_console = number
    coordinator   = number
    simulator     = number
    control_api   = number
    web           = number
  })
  default = {
    kafka         = 19092
    postgres      = 15432
    minio_api     = 19000
    minio_console = 19001
    coordinator   = 18081
    simulator     = 18070
    control_api   = 18080
    web           = 8088
  }

  validation {
    condition = (
      alltrue([for port in values(var.host_ports) : port >= 1 && port <= 65535 && floor(port) == port]) &&
      length(distinct(values(var.host_ports))) == length(values(var.host_ports))
    )
    error_message = "Host ports must be distinct integers between 1 and 65535."
  }
}
