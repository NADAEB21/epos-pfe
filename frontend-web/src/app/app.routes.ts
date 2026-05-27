import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    canActivate: [guestGuard],
    loadComponent: () =>
      import('./features/auth/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./core/layout/app-shell.component').then((m) => m.AppShellComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      {
        path: 'dashboard',
        loadComponent: () =>
          import('./shared/stub-page.component').then((m) => m.StubPageComponent),
        data: { title: 'Tableau de bord', figmaRef: 'tableau-de-bord.png' },
      },
      {
        path: 'etudiants',
        loadComponent: () =>
          import('./shared/stub-page.component').then((m) => m.StubPageComponent),
        data: { title: 'Gestion des Étudiants', figmaRef: 'gestion-des-etudiants.png' },
      },
      {
        path: 'examens-grilles',
        loadComponent: () =>
          import('./shared/stub-page.component').then((m) => m.StubPageComponent),
        data: {
          title: 'Examens & Grilles',
          figmaRef: 'conception-grille-evaluation.png',
        },
      },
      {
        path: 'planning',
        loadComponent: () =>
          import('./shared/stub-page.component').then((m) => m.StubPageComponent),
        data: {
          title: 'Planification & Dispatching',
          figmaRef: 'planification-dispatching-automatique.png',
        },
      },
      {
        path: 'analyses-ia',
        loadComponent: () =>
          import('./shared/stub-page.component').then((m) => m.StubPageComponent),
        data: { title: 'Analyses IA', figmaRef: '(pas de design)' },
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
