# Separate from Compose's aether network; DNS aliases preserve the contract.
resource "docker_network" "aether" {
  name = "aether-terraform"
}

locals {
  log_opts = {
    "max-size" = "10m"
    "max-file" = "3"
  }
}
