import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthStore } from '../auth/auth.store';

interface NavItem {
  label: string;
  link: string;
  // Exact match for the active class — needed for parent links like /admin
  // whose path is a prefix of their children (/admin/utilisateurs, …).
  exact?: boolean;
}

interface NavGroup {
  title: string;
  items: NavItem[];
}

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './sidebar.component.html',
  styleUrl: './sidebar.component.scss',
})
export class SidebarComponent {
  private readonly authStore = inject(AuthStore);

  // JWT authorities drive nav visibility. The Responsable workspace is gated on
  // the RESPONSABLE_MATIERE role — a pure Super-admin does not own a matiere, so
  // they get the Administration zone instead, not this matiere-scoped workspace.
  // Compound users (SUPER_ADMIN + RESPONSABLE) see both zones concurrently.
  readonly groups = computed<NavGroup[]>(() => {
    const isSuperAdmin = this.authStore.isSuperAdmin();
    const isResponsable = this.authStore.isResponsable();

    const groups: NavGroup[] = [];

    if (isResponsable) {
      groups.push({
        title: 'Espace de travail',
        items: [
          { label: 'Accueil', link: '/accueil' },
          { label: 'Mes examens', link: '/examens' },
          { label: 'Bibliotheque', link: '/bibliotheque' },
        ],
      });
    }

    if (isResponsable) {
      groups.push({
        title: 'Mon equipe',
        items: [
          { label: 'Evaluateurs', link: '/equipe/evaluateurs' },
          { label: 'Co-responsables', link: '/equipe/co-responsables' },
        ],
      });
    }

    const parametres: NavItem[] = [{ label: 'Mon profil', link: '/parametres/profil' }];
    if (isResponsable) parametres.push({ label: 'Ma matiere', link: '/parametres/matiere' });
    groups.push({ title: 'Parametres', items: parametres });

    if (isSuperAdmin) {
      groups.push({
        title: 'Administration',
        items: [
          { label: "Vue d'ensemble", link: '/admin', exact: true },
          { label: 'Utilisateurs', link: '/admin/utilisateurs' },
          { label: 'Matieres', link: '/admin/matieres' },
          { label: 'Templates globaux', link: '/admin/templates' },
          { label: 'Examens', link: '/admin/examens' },
        ],
      });
    }

    return groups;
  });
}
