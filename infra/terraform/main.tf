# Real, apply-able Terraform targeting DigitalOcean. Provisions a DOKS
# (DigitalOcean Kubernetes) cluster sized for this project's three
# lightweight FastAPI services -- see infra/k8s/ for the manifests that get
# applied to it.
#
# Auth: set the DIGITALOCEAN_TOKEN env var before running (not committed to
# this file). The container registry (registry.digitalocean.com/bot-lisa)
# was created manually via the DO console, not by this file -- see the
# comment near the bottom for how to bring it under Terraform management
# later if desired.
#
# Usage:
#   export DIGITALOCEAN_TOKEN="..."
#   terraform init
#   terraform plan
#   terraform apply

terraform {
  required_version = ">= 1.5"
  required_providers {
    digitalocean = {
      source  = "digitalocean/digitalocean"
      version = "~> 2.0"
    }
  }
}

provider "digitalocean" {
  # Reads DIGITALOCEAN_TOKEN from the environment automatically --
  # intentionally no token value here.
}

# Picks the latest available Kubernetes version in this region instead of
# hardcoding one that may age out of DOKS's supported list.
data "digitalocean_kubernetes_versions" "current" {}

resource "digitalocean_kubernetes_cluster" "bot_lisa" {
  name                 = "bot-lisa-cluster"
  region               = "sfo3" # same region as the bot-lisa container registry
  version              = data.digitalocean_kubernetes_versions.current.latest_version
  registry_integration = true # grants this cluster pull access to the bot-lisa registry automatically

  node_pool {
    name       = "bot-lisa-pool"
    size       = "s-1vcpu-2gb" # smallest DOKS worker size -- fine for 3 low-traffic FastAPI services
    node_count = 1             # single node for a learning/dev cluster; raise once this needs real redundancy
  }
}

output "cluster_id" {
  value = digitalocean_kubernetes_cluster.bot_lisa.id
}

output "cluster_endpoint" {
  value = digitalocean_kubernetes_cluster.bot_lisa.endpoint
}

# --- Not yet managed here ---
#
# digitalocean_container_registry.bot_lisa -- the registry already exists
# (created manually via the console). To bring it under Terraform instead of
# leaving it out-of-band, add:
#
#   resource "digitalocean_container_registry" "bot_lisa" {
#     name                   = "bot-lisa"
#     subscription_tier_slug = "starter"
#     region                 = "sfo3"
#   }
#
# then `terraform import digitalocean_container_registry.bot_lisa bot-lisa`
# so Terraform adopts it instead of trying to create a duplicate.
#
# Registry pull access for the cluster: DOKS doesn't automatically wire up
# imagePullSecrets for a DO registry. After `terraform apply` creates the
# cluster, run:
#   doctl kubernetes cluster registry add bot-lisa-cluster
# This grants every node pool in the cluster pull access to
# registry.digitalocean.com/bot-lisa -- needed before `kubectl apply -f
# infra/k8s/` will succeed at pulling the bot-lisa image.
