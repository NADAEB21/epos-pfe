import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthStore } from '../auth/auth.store';
import { IconComponent, IconName } from '../../shared/ui/icon.component';
import { LayoutStore } from './layout.store';

interface NavItem {
  label: string;
  link: string;
  icon: IconName;
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
  imports: [RouterLink, RouterLinkActive, IconComponent],
  templateUrl: './sidebar.component.html',
  styleUrl: './sidebar.component.scss',
})
export class SidebarComponent {
  private readonly authStore = inject(AuthStore);
  readonly layout = inject(LayoutStore);

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
          { label: 'Accueil', link: '/accueil', icon: 'home' },
          { label: 'Mes examens', link: '/examens', icon: 'clipboard' },
          { label: 'Bibliothèque', link: '/bibliotheque', icon: 'book' },
          { label: 'Tendances', link: '/tendances', icon: 'trend' },
        ],
      });
    }

    if (isResponsable) {
      groups.push({
        title: 'Mon équipe',
        items: [
          { label: 'Évaluateurs', link: '/equipe/evaluateurs', icon: 'users' },
          { label: 'Co-responsables', link: '/equipe/co-responsables', icon: 'academic' },
        ],
      });
    }

    // « Ma matière » supprimée (W2/D3) — voir ADR-0027 pour sa seule résurrection prévue.
    groups.push({
      title: 'Paramètres',
      items: [{ label: 'Mon profil', link: '/parametres/profil', icon: 'user' }],
    });

    if (isSuperAdmin) {
      groups.push({
        title: 'Administration',
        items: [
          { label: "Vue d'ensemble", link: '/admin', icon: 'building', exact: true },
          { label: 'Utilisateurs', link: '/admin/utilisateurs', icon: 'users' },
          { label: 'Matières', link: '/admin/matieres', icon: 'layers' },
          // « Templates globaux » supprimé (W2/ADR-0027 — autorité pédagogique).
          { label: 'Examens', link: '/admin/examens', icon: 'eye' },
          { label: 'Synthèse', link: '/admin/synthese', icon: 'chart' },
        ],
      });
    }

    return groups;
  });
}
