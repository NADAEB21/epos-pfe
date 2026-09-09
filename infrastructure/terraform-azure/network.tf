# Public IP of the machine running Terraform, used to lock SSH down to just it.
data "http" "my_ip" {
  count = var.ssh_allowed_cidr == null ? 1 : 0
  url   = "https://checkip.amazonaws.com"
}

resource "random_string" "dns" {
  length  = 6
  upper   = false
  special = false
}

locals {
  ssh_cidr = coalesce(
    var.ssh_allowed_cidr,
    try("${chomp(data.http.my_ip[0].response_body)}/32", null)
  )
  dns_label = coalesce(var.dns_label, "${var.project}-${random_string.dns.result}")
  tags = {
    Project     = var.project
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}

resource "azurerm_resource_group" "this" {
  name     = "${var.project}-${var.environment}-rg"
  location = var.location
  tags     = local.tags
}

resource "azurerm_virtual_network" "this" {
  name                = "${var.project}-vnet"
  resource_group_name = azurerm_resource_group.this.name
  location            = azurerm_resource_group.this.location
  address_space       = ["10.20.0.0/16"]
  tags                = local.tags
}

# Single public subnet: Postgres runs as a container on the VM itself, so there
# is nothing to isolate in a private subnet and no NAT gateway to pay for.
resource "azurerm_subnet" "public" {
  name                 = "${var.project}-public"
  resource_group_name  = azurerm_resource_group.this.name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = ["10.20.1.0/24"]
}

# Standard SKU + Static: the address is allocated at creation, before the VM
# exists, so the hostname can be baked into the bootstrap. It survives VM
# replacement and deallocation, which keeps the URL and certificate valid.
resource "azurerm_public_ip" "this" {
  name                = "${var.project}-pip"
  resource_group_name = azurerm_resource_group.this.name
  location            = azurerm_resource_group.this.location
  allocation_method   = "Static"
  sku                 = "Standard"
  ip_version          = "IPv4"
  domain_name_label   = local.dns_label
  tags                = local.tags
}

resource "azurerm_network_security_group" "app" {
  name                = "${var.project}-app-nsg"
  resource_group_name = azurerm_resource_group.this.name
  location            = azurerm_resource_group.this.location
  tags                = local.tags

  security_rule {
    name                       = "ssh-operator"
    description                = "SSH for deployments, operator IP only"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "22"
    source_address_prefix      = local.ssh_cidr
    destination_address_prefix = "*"
  }

  # Port 80 stays open even though the app is HTTPS-only: Caddy answers the
  # ACME HTTP-01 challenge here, then redirects to 443.
  security_rule {
    name                       = "http"
    description                = "HTTP (ACME challenge + redirect to HTTPS)"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "80"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "https"
    description                = "HTTPS (Angular app, REST API, STOMP WebSocket)"
    priority                   = 120
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "443"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }
  # The built-in Azure rules already deny every other inbound flow and allow
  # all outbound (package installs, Maven/npm, ACME).
}

resource "azurerm_network_interface" "app" {
  name                = "${var.project}-nic"
  resource_group_name = azurerm_resource_group.this.name
  location            = azurerm_resource_group.this.location
  tags                = local.tags

  ip_configuration {
    name                          = "primary"
    subnet_id                     = azurerm_subnet.public.id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.this.id
  }
}

resource "azurerm_network_interface_security_group_association" "app" {
  network_interface_id      = azurerm_network_interface.app.id
  network_security_group_id = azurerm_network_security_group.app.id
}
