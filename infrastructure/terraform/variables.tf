variable "aws_region" {
  description = "AWS region. eu-west-3 (Paris) is ~35 ms from Tunis."
  type        = string
  default     = "eu-west-3"
}

variable "project" {
  description = "Name prefix applied to every resource."
  type        = string
  default     = "epos"
}

variable "environment" {
  description = "Environment tag. This stack is a mid-development deployment, not production."
  type        = string
  default     = "dev"
}

variable "instance_type" {
  description = <<-EOT
    EC2 size. 4 GiB is the practical floor: the stack runs five JVMs plus
    Postgres. Heap is capped per service in docker-compose.prod.yml and user_data
    adds 2 GiB of swap so the Maven and Angular builds do not OOM.

    Constrained by the account's Free plan, which rejects RunInstances for any
    type not on the free-tier-eligible list (t3.medium is NOT on it). Eligible
    options, from `aws ec2 describe-instance-types --filters
    Name=free-tier-eligible,Values=true`:

      m7i-flex.large  2 vCPU /  8 GiB  ~$72/mo  roomier, faster builds
      c7i-flex.large  2 vCPU /  4 GiB  ~$55/mo  <- default
      t3.small        2 vCPU /  2 GiB  ~$17/mo  too tight for five JVMs
      t3.micro        2 vCPU /  1 GiB           cannot run this stack

    Stay on x86_64: the t4g options are ARM and would need a different AMI.
  EOT
  type        = string
  default     = "c7i-flex.large"
}

variable "root_volume_gb" {
  description = "Root EBS size. The default 8 GiB is far too small for the Maven cache plus five service images."
  type        = number
  default     = 30
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
  description = <<-EOT
    Application timezone, per ADR-0010. The exam clock must follow Tunisian wall
    time regardless of where the server runs, so this stays Africa/Tunis even
    though the local .env pins Europe/Paris to match a dev laptop.
  EOT
  type        = string
  default     = "Africa/Tunis"
}

variable "db_username" {
  description = "Postgres superuser for the containerised database."
  type        = string
  default     = "epos"
}
