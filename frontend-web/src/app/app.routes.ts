import { Routes } from '@angular/router';
import { ExamenWorkspaceStore } from './features/examens/workspace/examen-workspace.store';
import {
  authGuard,
  guestGuard,
  landingRedirectGuard,
  responsableGuard,
  superAdminGuard,
  webAccessGuard,
} from './core/auth/auth.guard';

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
      import('./features/examens/workspace/vue-ensemble.component').then((m) => m.VueEnsembleComponent),
  },
  {
    path: 'stations-grilles',
    loadComponent: () =>
      import('./features/examens/preparation/stations-grilles.component').then(
        (m) => m.StationsGrillesComponent,
      ),
  },
  {
    path: 'etudiants',
    loadComponent: () =>
      import('./features/examens/preparation/etudiants.component').then((m) => m.EtudiantsComponent),
  },
  {
    path: 'lots',
    loadComponent: () =>
      import('./features/examens/preparation/lots.component').then((m) => m.LotsComponent),
  },
  {
    path: 'convocations',
    loadComponent: () =>
      import('./features/examens/preparation/convocations.component').then((m) => m.ConvocationsComponent),
  },
  { path: 'planning', ...stub('Planning') },
  {
    path: 'lancement',
    loadComponent: () =>
      import('./features/examens/jour-j/lancement.component').then((m) => m.LancementComponent),
  },
  {
    path: 'suivi',
    loadComponent: () =>
      import('./features/examens/jour-j/suivi.component').then((m) => m.SuiviComponent),
  },
  {
    path: 'resultats',
    loadComponent: () =>
      import('./features/examens/resultats/resultats.component').then((m) => m.ResultatsComponent),
  },
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
    // Authenticated but role-less for the web (pure EVALUATEUR) — sent here by
    // webAccessGuard. Rendered outside the shell (no sidebar).
    path: 'acces-refuse',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/access/acces-refuse.component').then((m) => m.AccesRefuseComponent),
  },
  {
    path: '',
    canActivate: [authGuard, webAccessGuard],
    loadComponent: () =>
      import('./core/layout/app-shell.component').then((m) => m.AppShellComponent),
    children: [
      // Role-aware landing: Responsable → /accueil, pure Super-admin → /admin.
      { path: '', pathMatch: 'full', canActivate: [landingRedirectGuard], children: [] },

      // Responsable workspace — gated as a group on the RESPONSABLE_MATIERE
      // role. A pure Super-admin who deep-links here is bounced to /admin by
      // responsableGuard, so the whole matiere-scoped zone (not just /accueil)
      // stays out of reach via the URL bar.
      {
        path: '',
        canActivate: [responsableGuard],
        children: [
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
              import('./features/examens/examens-list.component').then(
                (m) => m.ExamensListComponent,
              ),
          },
          {
            // MUST precede 'examens/:id' — otherwise 'nouveau' is captured as an id.
            path: 'examens/nouveau',
            loadComponent: () =>
              import('./features/examens/examen-create.component').then(
                (m) => m.ExamenCreateComponent,
              ),
          },
          {
            path: 'examens/:id',
            // Route-scoped: one store instance shared by the workspace shell and
            // every tab, so a lifecycle change in Lancement reactively updates the
            // parent's status-aware tabs + lifecycle bar.
            providers: [ExamenWorkspaceStore],
            loadComponent: () =>
              import('./features/examens/workspace/examen-workspace.component').then(
                (m) => m.ExamenWorkspaceComponent,
              ),
            children: workspaceTabs,
          },
          {
            path: 'bibliotheque',
            loadComponent: () =>
              import('./features/bibliotheque/bibliotheque.component').then(
                (m) => m.BibliothequeComponent,
              ),
          },

          // Mon equipe
          { path: 'equipe/evaluateurs', ...stub('Evaluateurs') },
          { path: 'equipe/co-responsables', ...stub('Co-responsables') },

          // Parametres (matiere-scoped)
          { path: 'parametres/matiere', ...stub('Ma matiere') },
        ],
      },

      // Parametres (any web user)
      { path: 'parametres/profil', ...stub('Mon profil') },

      // Administration (SUPER_ADMIN only)
      {
        path: 'admin',
        canActivate: [superAdminGuard],
        children: [
          {
            path: '',
            pathMatch: 'full',
            loadComponent: () =>
              import('./features/admin/admin-home.component').then((m) => m.AdminHomeComponent),
          },
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
