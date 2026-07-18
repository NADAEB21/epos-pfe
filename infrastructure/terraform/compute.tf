data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

# Deployment key. Generated here so the stack is reproducible from nothing; the
# private key is written next to the Terraform config and is gitignored.
resource "tls_private_key" "deploy" {
  algorithm = "RSA"
  rsa_bits  = 4096
}

resource "aws_key_pair" "deploy" {
  key_name   = "${var.project}-${var.environment}-deploy"
  public_key = tls_private_key.deploy.public_key_openssh
}

resource "local_sensitive_file" "private_key" {
  filename        = "${path.module}/${var.project}-deploy.pem"
  content         = tls_private_key.deploy.private_key_pem
  file_permission = "0600"
}

# Allocated before the instance so user_data can bake the final hostname into
# the env file. Because the EIP is a standalone resource, referencing it here
# creates no dependency cycle, and the hostname survives instance replacement.
resource "aws_eip" "this" {
  domain = "vpc"
  tags   = { Name = "${var.project}-eip" }
}

locals {
  # sslip.io resolves any hostname containing an embedded IP back to that IP,
  # which gives Caddy a real DNS name to obtain a Let's Encrypt certificate for
  # without owning a domain. 13.38.1.2 -> epos.13-38-1-2.sslip.io
  app_domain = "${var.project}.${replace(aws_eip.this.public_ip, ".", "-")}.sslip.io"
}

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.app.id]
  key_name               = aws_key_pair.deploy.key_name
  iam_instance_profile   = aws_iam_instance_profile.instance.name

  root_block_device {
    volume_size           = var.root_volume_gb
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  metadata_options {
    http_tokens   = "required" # IMDSv2 only
    http_endpoint = "enabled"
  }

  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    region       = var.aws_region
    jwt_param    = aws_ssm_parameter.jwt_secret.name
    db_param     = aws_ssm_parameter.db_password.name
    db_username  = var.db_username
    domain       = local.app_domain
    app_timezone = var.app_timezone
  })

  tags = { Name = "${var.project}-app" }
}

resource "aws_eip_association" "this" {
  instance_id   = aws_instance.app.id
  allocation_id = aws_eip.this.id
}
