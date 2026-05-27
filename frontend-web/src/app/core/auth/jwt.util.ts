import { jwtDecode } from 'jwt-decode';
import { CurrentUser, JwtPayload, ParsedAuthority, RoleType } from './auth.models';

export function decodeJwt(token: string): JwtPayload {
  return jwtDecode<JwtPayload>(token);
}

export function parseAuthority(authority: string): ParsedAuthority | null {
  if (!authority.startsWith('ROLE_')) return null;
  const stripped = authority.substring('ROLE_'.length);
  const colonIdx = stripped.indexOf(':');
  if (colonIdx === -1) {
    return { role: stripped as RoleType, matiereId: null };
  }
  const role = stripped.substring(0, colonIdx) as RoleType;
  const parsed = Number(stripped.substring(colonIdx + 1));
  return { role, matiereId: Number.isFinite(parsed) ? parsed : null };
}

export function payloadToCurrentUser(payload: JwtPayload): CurrentUser {
  return {
    email: payload.sub,
    userId: payload.userId,
    authorities: (payload.authorities ?? [])
      .map(parseAuthority)
      .filter((a): a is ParsedAuthority => a !== null),
    accessTokenExpiresAt: new Date(payload.exp * 1000),
  };
}

export function isExpired(token: string, skewMs = 0): boolean {
  try {
    const { exp } = decodeJwt(token);
    return Date.now() + skewMs >= exp * 1000;
  } catch {
    return true;
  }
}
