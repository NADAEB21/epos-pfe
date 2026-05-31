import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthStore } from '../auth/auth.store';

interface NavItem {
  label: string;
  link: string;
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

  // JWT authorities drive nav visibility. Compound users (e.g. SUPER_ADMIN +
  // RESPONSABLE) see both zones concurrently (decision 4 of the Phase B plan).
  readonly groups = computed<NavGroup[]>(() => {
    const isSuperAdmin = this.authStore.hasGlobalRole('SUPER_ADMIN');
    const isResponsable = this.authStore.responsableMatiereIds().length > 0;
    const canWorkspace = isSuperAdmin || isResponsable;

    const groups: NavGroup[] = [];

    if (canWorkspace) {
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
