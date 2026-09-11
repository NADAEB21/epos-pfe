variable "subscription_id" {
  description = "Azure subscription to deploy into (`az account show --query id -o tsv`). Null falls back to ARM_SUBSCRIPTION_ID."
  type        = string
  default     = null
}

variable "location" {
  description = <<-EOT
    Azure region. France Central (Paris) is ~35 ms from Tunis, same latency
    class as AWS eu-west-3. Azure for Students subscriptions are sometimes
    refused VM sizes in a given region ("not available in this region for this
    subscription"): if apply fails that way, try "westeurope" or "northeurope".
  EOT
  type        = string
  default     = "francecentral"
}

variable "project" {
  description = "Name prefix applied to every resource."
  type        = string
  default     = "epos"
}

variable "environment" {
  description = "Environment tag. This stack is a demo/mid-development deployment, not production."
  type        = string
  default     = "dev"
}

variable "vm_size" {
  description = <<-EOT
    VM size. 4 GiB is the practical floor: five JVMs plus Postgres plus the
    Python ai-service. Heap is capped per service in docker-compose.prod.yml
    and user_data adds 2 GiB of swap so the Maven and Angular builds do not OOM.

      Standard_B2s      2 vCPU / 4 GiB   ~$30/mo   <- default
      Standard_B2ms     2 vCPU / 8 GiB   ~$60/mo   roomier, faster builds
      Standard_B2as_v2  2 vCPU / 8 GiB   ~$50/mo   AMD, cheaper 8 GiB option

    B-series is burstable: a build spikes, then the CPU credit refills while
    the stack idles, which fits a demo box. Against Azure for Students'
    $100 / 12-month credit, B2s runs about three months continuously, so
    deallocate the VM whenever it is not needed (see README).
  EOT
  type        = string
  default     = "Standard_B2s"
}

variable "os_disk_gb" {
  description = "OS disk size. 30 GiB holds the Maven/npm caches plus eight service images."
  type        = number
  default     = 30
}

variable "admin_username" {
  description = "Linux admin user created on the VM. deploy.sh reads it from the ssh_user output."
  type        = string
  default     = "epos"
}

variable "dns_label" {
  description = <<-EOT
    DNS label for the public IP. The resulting hostname is
    <label>.<location>.cloudapp.azure.com: Azure-owned DNS, no third party
    in the path. Must be unique within the region. Leave null to generate
    <project>-<random>.
  EOT
  type        = string
  default     = null
}

variable "ssh_allowed_cidr" {
  description = <<-EOT
    CIDR permitted to reach port 22. Leave null to auto-detect this machine's
    public IP and lock SSH to it alone. Set explicitly (e.g. "197.x.x.x/32") if
    you deploy from a different network, or "0.0.0.0/0" to allow anywhere
    (not recommended).
  EOT
  type        = string
  default     = null
}

variable "app_timezone" {
  description = "Application timezone, per ADR-0010: the exam clock follows Tunisian wall time wherever the server runs."
  type        = string
  default     = "Africa/Tunis"
}

variable "db_username" {
  description = "Postgres superuser for the containerised database."
  type        = string
  default     = "epos"
}
