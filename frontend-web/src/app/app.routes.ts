import { Routes } from '@angular/router';
import { authGuard, guestGuard, superAdminGuard } from './core/auth/auth.guard';

const stub = (title: string, figmaRef = '(a venir)') => ({
  loadComponent: () => import('./shared/stub-page.component').then((m) => m.StubPageComponent),
  data: { title, figmaRef },
});

// Per-exam workspace tabs all render the stub for now (Phase B ships the shell
// + status-aware tab list only; tab content is a session each).
const workspaceTabs: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'vue-ensemble' },
  {
    path: 'vue-ensemble',
    loadComponent: () =>
      import('./features/examens/vue-ensemble.component').then((m) => m.VueEnsembleComponent),
  },
  {
    path: 'stations-grilles',
    loadComponent: () =>
      import('./features/examens/stations-grilles.component').then(
        (m) => m.StationsGrillesComponent,
      ),
  },
  { path: 'etudiants', ...stub('Etudiants') },
  { path: 'planning', ...stub('Planning') },
  { path: 'lancement', ...stub('Lancement') },
  { path: 'suivi', ...stub('Suivi en direct') },
  { path: 'resultats', ...stub('Resultats') },
  { path: 'analyses-ia', ...stub('Analyses IA') },
];

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
      { path: '', pathMatch: 'full', redirectTo: 'accueil' },

      // Espace de travail
      {
        path: 'accueil',
        loadComponent: () =>
          import('./features/home/accueil.component').then((m) => m.AccueilComponent),
      },
      {
        path: 'examens',
        pathMatch: 'full',
        loadComponent: () =>
          import('./features/examens/examens-list.component').then((m) => m.ExamensListComponent),
      },
      {
        path: 'examens/:id',
        loadComponent: () =>
          import('./features/examens/examen-workspace.component').then(
            (m) => m.ExamenWorkspaceComponent,
          ),
        children: workspaceTabs,
      },
      { path: 'bibliotheque', ...stub('Bibliotheque de grilles') },

      // Mon equipe
      { path: 'equipe/evaluateurs', ...stub('Evaluateurs') },
      { path: 'equipe/co-responsables', ...stub('Co-responsables') },

      // Parametres
      { path: 'parametres/profil', ...stub('Mon profil') },
      { path: 'parametres/matiere', ...stub('Ma matiere') },

      // Administration (SUPER_ADMIN only)
      {
        path: 'admin',
        canActivate: [superAdminGuard],
        children: [
          { path: 'utilisateurs', ...stub('Utilisateurs (tous)') },
          { path: 'matieres', ...stub('Matieres (catalogue)') },
          { path: 'templates', ...stub('Templates globaux') },
          { path: 'examens', ...stub('Examens (oversight)') },
        ],
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
