import { Routes } from '@angular/router';
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

// Onglets pour l'espace de travail d'un examen
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
  {
    path: 'etudiants',
    loadComponent: () =>
      import('./features/examens/etudiants.component').then((m) => m.EtudiantsComponent),
  },
  { path: 'planning', ...stub('Planning') },
  { path: 'lancement', ...stub('Lancement') },
  { path: 'suivi', ...stub('Suivi en direct') },
  { path: 'resultats', ...stub('Resultats') },
  { path: 'analyses-ia', ...stub('Analyses IA') },
];

export const routes: Routes = [
  {
    path: 'login',
    // canActivate: [guestGuard], // Désactivé pour le test
    loadComponent: () =>
      import('./features/auth/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'acces-refuse',
    loadComponent: () =>
      import('./features/access/acces-refuse.component').then((m) => m.AccesRefuseComponent),
  },
  {
    path: '',
    // canActivate: [authGuard, webAccessGuard], // DÉSACTIVÉ POUR LE TEST
    loadComponent: () =>
      import('./core/layout/app-shell.component').then((m) => m.AppShellComponent),
    children: [
      { path: '', pathMatch: 'full', canActivate: [landingRedirectGuard], children: [] },

      // Espace Responsable
      {
        path: '',
        // canActivate: [responsableGuard], 
        children: [
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
            path: 'examens/:id',
            loadComponent: () =>
              import('./features/examens/examen-workspace.component').then(
                (m) => m.ExamenWorkspaceComponent,
              ),
            children: workspaceTabs,
          },
          { path: 'bibliotheque', ...stub('Bibliotheque de grilles') },
          { path: 'equipe/evaluateurs', ...stub('Evaluateurs') },
          { path: 'equipe/co-responsables', ...stub('Co-responsables') },
          { path: 'parametres/matiere', ...stub('Ma matiere') },
        ],
      },

      { path: 'parametres/profil', ...stub('Mon profil') },

      // --- ADMINISTRATION (VOS INTERFACES) ---
      {
        path: 'admin',
        // canActivate: [superAdminGuard], // DÉSACTIVÉ POUR LE TEST
        children: [
          {
            path: '',
            pathMatch: 'full',
            loadComponent: () =>
              import('./features/admin/admin-home.component').then((m) => m.AdminHomeComponent),
          },
          {
            path: 'utilisateurs',
            loadComponent: () =>
              import('./features/user/user.component').then((m) => m.UserComponent),
          },
          {
            path: 'examens',
            loadComponent: () =>
              import('./features/examen-admin/examen-admin.component').then((m) => m.ExamenAdminComponent),
          },
          {
            path: 'stations',
            loadComponent: () =>
              import('./features/station/station.component').then((m) => m.StationComponent),
          },
          { path: 'matieres', ...stub('Matieres (catalogue)') },

          // --- ROUTE MISE À JOUR : TEMPLATES GLOBAUX ---
          {
            path: 'templates',
            loadComponent: () =>
              import('./features/template/template.component').then((m) => m.TemplateComponent),
          },
        ],
      },
    ],
  },
  { path: '**', redirectTo: '' },
];