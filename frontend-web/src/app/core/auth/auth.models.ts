export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
}

export interface RefreshRequest {
  refreshToken: string;
}

export interface JwtPayload {
  sub: string;
  userId: number;
  authorities: string[];
  exp: number;
  iat: number;
}

export type RoleType = 'SUPER_ADMIN' | 'RESPONSABLE_MATIERE' | 'EVALUATEUR';

export interface ParsedAuthority {
  role: RoleType;
  matiereId: number | null;
}

export interface CurrentUser {
  email: string;
  userId: number;
  authorities: ParsedAuthority[];
  accessTokenExpiresAt: Date;
}

export interface ApiResponse<T> {
  success: boolean;
  data: T | null;
  message: string | null;
}
