# Secrets are generated here and stored as SSM SecureStrings; the instance pulls
# them at boot via its IAM role. They are deliberately NOT baked into user_data,
# which is readable by anything that can reach the instance metadata service.
#
# special = false on both: these values land in an .env file that docker compose
# interpolates, where a literal "$" would be eaten as a variable reference.

# 64 bytes -> HmacJwtDecoders.autoSelectByLength picks HS512 consistently across
# auth-service (signer), api-gateway, exam-service and scoring-service (verifiers).
resource "random_password" "jwt_secret" {
  length  = 64
  special = false
}

resource "random_password" "db_password" {
  length  = 32
  special = false
}

resource "aws_ssm_parameter" "jwt_secret" {
  name        = "/${var.project}/${var.environment}/jwt_secret"
  description = "EPOS JWT signing secret (HS512)"
  type        = "SecureString"
  value       = random_password.jwt_secret.result
}

resource "aws_ssm_parameter" "db_password" {
  name        = "/${var.project}/${var.environment}/db_password"
  description = "EPOS Postgres password"
  type        = "SecureString"
  value       = random_password.db_password.result
}
