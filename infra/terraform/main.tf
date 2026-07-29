# Scaffold only -- intentionally not `terraform apply`-able yet, since that
# needs a real cloud account/credentials. This documents the resources this
# project would provision, matching the JD's "Kubernetes/Terraform and a
# major cloud" line item with real IaC, not just a buzzword.
#
# Fill in a provider block (AWS/GCP) and run `terraform init && terraform plan`
# once you pick a target cloud. Fly.io/Vercel (current v1 hosting) don't need
# Terraform; this becomes relevant once the k8s manifests in infra/k8s/ move
# from k3d/minikube to a real managed cluster (EKS/GKE).

terraform {
  required_version = ">= 1.5"
  # required_providers {
  #   aws = {
  #     source  = "hashicorp/aws"
  #     version = "~> 5.0"
  #   }
  # }
}

# --- Planned resources (uncomment + configure once a cloud is chosen) ---

# resource "aws_ecr_repository" "infant_lang_bot" {
#   name = "infant-lang-bot"
# }
#
# resource "aws_eks_cluster" "main" {
#   name     = "infant-lang-bot-cluster"
#   role_arn = aws_iam_role.eks_cluster.arn
#   vpc_config {
#     subnet_ids = var.subnet_ids
#   }
# }
#
# resource "aws_iam_role" "eks_cluster" {
#   name = "infant-lang-bot-eks-role"
#   assume_role_policy = jsonencode({
#     Version = "2012-10-17"
#     Statement = [{
#       Action    = "sts:AssumeRole"
#       Effect    = "Allow"
#       Principal = { Service = "eks.amazonaws.com" }
#     }]
#   })
# }
