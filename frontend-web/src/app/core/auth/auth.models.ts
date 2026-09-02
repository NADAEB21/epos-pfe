export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
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
  /**
   * Identité SERVIE par GET /auth/me, posée après coup (le JWT ne porte ni nom
   * ni prénom — il n'a pas à le faire). Absents tant que l'appel n'a pas
   * abouti : les écrans gardent alors leur lecture de repli (l'e-mail). La
   * session ne dépend JAMAIS de ces champs.
   */
  nom?: string;
  prenom?: string;
  /** Rôle principal déterministe (précédence serveur), préféré à authorities[0]. */
  primaryRole?: RoleType;
}

export interface ApiResponse<T> {
  success: boolean;
  data: T ;
  message: string | null;
}
