import { APP_INITIALIZER, ApplicationConfig, LOCALE_ID, inject, isDevMode, provideZoneChangeDetection } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withRouterConfig } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AuthService } from './core/auth/auth.service';

// W5/ADR-0028 — sans LOCALE_ID, DatePipe et DecimalPipe rendaient « Aug 14,
// 2026 » et « 12.5 » dans une interface entièrement française (convocations
// comprises). La v1 est française : on le dit au framework.
registerLocaleData(localeFr);

export const appConfig: ApplicationConfig = {
  providers: [
    { provide: LOCALE_ID, useValue: 'fr' },
    provideZoneChangeDetection({ eventCoalescing: true }),
    // 'always' so the parent examens/:id param reaches the workspace TAB
    // components (vue-ensemble / stations-grilles / etudiants) via
    // withComponentInputBinding(). Default 'emptyOnly' does not inherit a
    // parent param into a non-empty-path child route, so the tabs' `id` input
    // was never set -> NaN -> the "Impossible de charger" error box.
    provideRouter(
      routes,
      withComponentInputBinding(),
      withRouterConfig({ paramsInheritanceStrategy: 'always' }),
    ),
    provideHttpClient(withInterceptors([authInterceptor])),
    {
      provide: APP_INITIALIZER,
      multi: true,
      useFactory: () => {
        const auth = inject(AuthService);
        return () => auth.hydrate();
      },
    },
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
