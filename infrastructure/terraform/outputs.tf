output "app_url" {
  description = "Public HTTPS entrypoint (Angular app + /api/v1 + /ws)."
  value       = "https://${local.app_domain}"
}

output "app_domain" {
  description = "Hostname Caddy obtains the Let's Encrypt certificate for."
  value       = local.app_domain
}

output "instance_ip" {
  description = "Elastic IP. Stable across instance replacement."
  value       = aws_eip.this.public_ip
}

output "instance_id" {
  description = "EC2 instance id (for: aws ssm start-session --target <id>)."
  value       = aws_instance.app.id
}

output "ssh_key_path" {
  description = "Generated private key used by scripts/deploy.ps1."
  value       = local_sensitive_file.private_key.filename
}

output "ssh_command" {
  description = "Shell into the instance."
  value       = "ssh -i ${local_sensitive_file.private_key.filename} ec2-user@${aws_eip.this.public_ip}"
}

output "mobile_dart_defines" {
  description = "Flags for Feten's Flutter build so the app targets this deployment."
  value       = "--dart-define=API_BASE_URL=https://${local.app_domain}/api/v1 --dart-define=WS_BASE_URL=https://${local.app_domain}"
}
