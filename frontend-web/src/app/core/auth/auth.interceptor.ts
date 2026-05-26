import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, finalize, shareReplay, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { TokenStorageService } from './token-storage.service';

let refreshInFlight$: Observable<string> | null = null;

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const tokens = inject(TokenStorageService);
  const auth = inject(AuthService);
  const router = inject(Router);

  const isAuthEndpoint = /\/auth\/(login|refresh|password-reset)/.test(req.url);
  const attach = (token: string) =>
    req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });

  const accessToken = tokens.getAccessToken();
  const authed = !isAuthEndpoint && accessToken ? attach(accessToken) : req;

  return next(authed).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status !== 401 || isAuthEndpoint || !accessToken) {
        return throwError(() => err);
      }

      // Coalesce concurrent refresh attempts: backend rotates the refresh-token
      // family on each use and treats a re-used token as a breach (revokeAll).
      // Two parallel refreshes would log the user out.
      if (!refreshInFlight$) {
        refreshInFlight$ = auth.refresh().pipe(
          shareReplay(1),
          finalize(() => {
            refreshInFlight$ = null;
          }),
        );
      }

      return refreshInFlight$.pipe(
        switchMap((newAccess) => next(attach(newAccess))),
        catchError((refreshErr) => {
          router.navigate(['/login']);
          return throwError(() => refreshErr);
        }),
      );
    }),
  );
};
