terraform {
  required_version = ">= 1.9"

  required_providers {
    azurerm = { source = "hashicorp/azurerm", version = "~> 4.0" }
    random  = { source = "hashicorp/random", version = "~> 3.6" }
    tls     = { source = "hashicorp/tls", version = "~> 4.0" }
    local   = { source = "hashicorp/local", version = "~> 2.5" }
    http    = { source = "hashicorp/http", version = "~> 3.4" }
  }
}

# Authentication comes from `az login` (Azure CLI). azurerm 4.x insists on an
# explicit subscription: pass it as `subscription_id` in terraform.tfvars or
# export ARM_SUBSCRIPTION_ID before running. `az account show` prints it.
provider "azurerm" {
  subscription_id = var.subscription_id

  features {
    resource_group {
      # Let `terraform destroy` remove the group even if something outside this
      # config (a diagnostic setting, a manually created disk) was left in it.
      prevent_deletion_if_contains_resources = false
    }
  }
}
