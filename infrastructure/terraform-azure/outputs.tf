output "app_url" {
  description = "Public HTTPS entrypoint (Angular app + /api/v1 + /ws)."
  value       = "https://${local.app_domain}"
}

output "app_domain" {
  description = "Hostname Caddy obtains the Let's Encrypt certificate for."
  value       = local.app_domain
}

output "instance_ip" {
  description = "Static public IP. Stable across VM replacement and deallocation."
  value       = azurerm_public_ip.this.ip_address
}

output "instance_id" {
  description = "VM resource id."
  value       = azurerm_linux_virtual_machine.app.id
}

output "resource_group" {
  description = "Resource group holding every resource (for az vm start/deallocate)."
  value       = azurerm_resource_group.this.name
}

output "vm_name" {
  description = "VM name (for az vm start/deallocate)."
  value       = azurerm_linux_virtual_machine.app.name
}

output "ssh_user" {
  description = "Login user on the VM. scripts/deploy.sh reads this."
  value       = var.admin_username
}

output "ssh_key_path" {
  description = "Generated private key used by scripts/deploy.sh."
  value       = local_sensitive_file.private_key.filename
}

output "ssh_command" {
  description = "Shell into the VM."
  value       = "ssh -i ${local_sensitive_file.private_key.filename} ${var.admin_username}@${azurerm_public_ip.this.ip_address}"
}

output "mobile_dart_defines" {
  description = "Flags for the Flutter release build so the app targets this deployment."
  value       = "--dart-define=API_BASE_URL=https://${local.app_domain}/api/v1 --dart-define=WS_BASE_URL=https://${local.app_domain}"
}
