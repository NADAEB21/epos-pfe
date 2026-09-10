# Deployment key. Generated here so the stack is reproducible from nothing; the
# private key is written next to the Terraform config and is gitignored.
resource "tls_private_key" "deploy" {
  algorithm = "RSA"
  rsa_bits  = 4096
}

resource "local_sensitive_file" "private_key" {
  filename        = "${path.module}/${var.project}-deploy.pem"
  content         = tls_private_key.deploy.private_key_pem
  file_permission = "0600"
}

locals {
  # Azure-owned hostname for the static public IP. Caddy obtains the Let's
  # Encrypt certificate for it; nothing outside Azure is in the DNS path.
  app_domain = azurerm_public_ip.this.fqdn
}

# Secrets are NOT provisioned here. The AWS recipe stores them in SSM and lets
# the instance pull them through an IAM role; the Azure equivalent (Key Vault +
# managed identity + RBAC propagation) is a lot of machinery for a value that
# only the VM ever needs. user_data generates JWT_SECRET and the Postgres
# password on the VM with openssl, so they never leave the box, and the
# operator reads /opt/epos/epos.env over SSH when debugging. The trade-off is
# that a replaced VM gets new secrets, which is fine because the Postgres
# volume lives on the OS disk and is replaced with it anyway.
resource "azurerm_linux_virtual_machine" "app" {
  name                = "${var.project}-app"
  resource_group_name = azurerm_resource_group.this.name
  location            = azurerm_resource_group.this.location
  size                = var.vm_size
  admin_username      = var.admin_username
  tags                = local.tags

  network_interface_ids = [azurerm_network_interface.app.id]

  disable_password_authentication = true

  admin_ssh_key {
    username   = var.admin_username
    public_key = tls_private_key.deploy.public_key_openssh
  }

  os_disk {
    name                 = "${var.project}-app-osdisk"
    caching              = "ReadWrite"
    storage_account_type = "StandardSSD_LRS"
    disk_size_gb         = var.os_disk_gb
  }

  # Ubuntu 24.04 LTS, Canonical's official Gen2 x86_64 image.
  source_image_reference {
    publisher = "Canonical"
    offer     = "ubuntu-24_04-lts"
    sku       = "server"
    version   = "latest"
  }

  # cloud-init runs this once, on first boot. It is not exposed through the
  # instance metadata service (unlike `user_data`), only through the root-owned
  # provisioning files, which is why the secrets may be generated inside it.
  # The body is scripts/bootstrap-vps.sh, shared with the plain-VPS path.
  custom_data = base64encode(templatefile("${path.module}/user_data.sh.tftpl", {
    admin_username = var.admin_username
    db_username    = var.db_username
    domain         = local.app_domain
    app_timezone   = var.app_timezone
    bootstrap      = file("${path.module}/../../scripts/bootstrap-vps.sh")
  }))
}
