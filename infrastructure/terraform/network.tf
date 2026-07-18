# Public IP of the machine running Terraform, used to lock SSH down to just it.
data "http" "my_ip" {
  count = var.ssh_allowed_cidr == null ? 1 : 0
  url   = "https://checkip.amazonaws.com"
}

locals {
  ssh_cidr = coalesce(
    var.ssh_allowed_cidr,
    try("${chomp(data.http.my_ip[0].response_body)}/32", null)
  )
}

data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_vpc" "this" {
  cidr_block           = "10.20.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${var.project}-vpc" }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.project}-igw" }
}

# Single public subnet. There is nothing to place in a private subnet while
# Postgres runs as a container on the instance itself, and skipping private
# subnets avoids a NAT gateway (~$32/month on its own).
resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.this.id
  cidr_block              = "10.20.1.0/24"
  availability_zone       = data.aws_availability_zones.available.names[0]
  map_public_ip_on_launch = true

  tags = { Name = "${var.project}-public" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = { Name = "${var.project}-public-rt" }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "app" {
  name        = "${var.project}-app-sg"
  description = "EPOS: public HTTP/HTTPS, operator-only SSH"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.project}-app-sg" }
}

# Port 80 must stay open to the world even though the app is HTTPS-only:
# Caddy answers the Let's Encrypt HTTP-01 challenge here, then redirects to 443.
resource "aws_vpc_security_group_ingress_rule" "http" {
  security_group_id = aws_security_group.app.id
  description       = "HTTP (ACME challenge + redirect to HTTPS)"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS (Angular app, REST API, STOMP WebSocket)"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "ssh" {
  security_group_id = aws_security_group.app.id
  description       = "SSH for deployments"
  cidr_ipv4         = local.ssh_cidr
  from_port         = 22
  to_port           = 22
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.app.id
  # No apostrophes: AWS restricts rule descriptions to [a-zA-Z0-9._-:/()#,@[]+=&;{}!$*]
  description       = "Outbound: package installs, Maven/npm, ACME"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}
