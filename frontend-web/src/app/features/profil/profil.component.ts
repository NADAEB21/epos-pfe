import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { MeResponse, ProfileApiService } from '../../core/api/profile-api.service';
import { MatiereResponse } from '../../core/api/models';

/**
 * W1 — « Mon profil » : le premier écran commun aux deux acteurs web.
 *
 * Trois blocs, décidés en S36 §4 et reconduits par le registre S39 :
 *  1. Qui je suis — lecture seule. L'e-mail est l'IDENTIFIANT de connexion :
 *     le modifier reviendrait à déplacer une identité (combiné à l'historique
 *     de #285, c'est le meilleur moyen de forker un compte) — non modifiable v1.
 *  2. Mes rôles et leurs portées — LA raison d'être de l'écran : c'est la
 *     réponse à « pourquoi je ne vois pas cet examen ? », question qui n'avait
 *     aucune réponse dans l'application.
 *  3. Changer mon mot de passe — l'endpoint attendait son écran. Le succès
 *     révoque tous les refresh tokens côté serveur : on l'assume à l'écran en
 *     fermant la session proprement au lieu de laisser l'utilisateur découvrir
 *     une déconnexion « mystérieuse » plus tard.
 */
@Component({
  selector: 'app-profil',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './profil.component.html',
})
export class ProfilComponent {
  private readonly profileApi = inject(ProfileApiService);
  private readonly directoryApi = inject(DirectoryApiService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);

  readonly me = signal<MeResponse | null>(null);
  private readonly matieres = signal<MatiereResponse[]>([]);
  readonly loading = signal(true);
  readonly loadError = signal(false);

  // ── Mot de passe ──────────────────────────────────────────────────────────
  readonly pwForm = this.fb.nonNullable.group({
    currentPassword: ['', Validators.required],
    // Politique serveur (UserCreateRequest) : 8+, une majuscule, un chiffre.
    newPassword: ['', [Validators.required, Validators.minLength(8), Validators.pattern(/^(?=.*[A-Z])(?=.*\d).+$/)]],
    confirm: ['', Validators.required],
  });
  readonly pwSubmitting = signal(false);
  readonly pwError = signal<string | null>(null);
  readonly pwDone = signal(false);

  /** Rôles décorés d'un libellé humain + du nom de la matière pour les portées. */
  readonly displayRoles = computed(() => {
    const me = this.me();
    if (!me) return [];
    const byId = new Map(this.matieres().map((m) => [m.id, m]));
    return (me.roles ?? []).map((r) => {
      const matiere = r.matiereId != null ? byId.get(r.matiereId) : undefined;
      switch (r.role) {
        case 'SUPER_ADMIN':
          return {
            label: 'Administrateur de la plateforme',
            scope: 'Gestion des comptes, du catalogue des matières et supervision — toutes matières en lecture.',
          };
        case 'RESPONSABLE_MATIERE':
          return {
            label: 'Responsable de matière',
            scope: matiere
              ? `${matiere.libelle} (${matiere.code}) — conception, conduite et résultats des épreuves de cette matière.`
              : `Matière n°${r.matiereId} — conception, conduite et résultats des épreuves de cette matière.`,
          };
        case 'EVALUATEUR':
          return {
            label: 'Évaluateur',
            scope: 'Notation sur l’application mobile, sur les stations qui vous sont affectées — toutes matières.',
          };
        default:
          return { label: r.role, scope: '' };
      }
    });
  });

  readonly initials = computed(() => {
    const me = this.me();
    if (!me) return '·';
    return `${(me.prenom?.[0] ?? '').toUpperCase()}${(me.nom?.[0] ?? '').toUpperCase()}` || '·';
  });

  constructor() {
    forkJoin({
      me: this.profileApi.me(),
      matieres: this.directoryApi.listMatieres().pipe(catchError(() => of([] as MatiereResponse[]))),
    }).subscribe({
      next: ({ me, matieres }) => {
        this.me.set(me);
        this.matieres.set(matieres);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.loading.set(false);
      },
    });
  }

  submitPassword(): void {
    this.pwError.set(null);
    const { currentPassword, newPassword, confirm } = this.pwForm.getRawValue();
    if (this.pwForm.invalid) {
      this.pwError.set(
        'Le nouveau mot de passe doit compter au moins 8 caractères, dont une majuscule et un chiffre.',
      );
      return;
    }
    if (newPassword !== confirm) {
      this.pwError.set('La confirmation ne correspond pas au nouveau mot de passe.');
      return;
    }
    this.pwSubmitting.set(true);
    this.profileApi.changePassword(currentPassword, newPassword).subscribe({
      next: () => {
        // Le serveur vient de révoquer tous les refresh tokens : la session ne
        // survivra pas. On le DIT puis on ferme proprement, plutôt que de
        // laisser une déconnexion inexpliquée frapper plus tard.
        this.pwDone.set(true);
        this.pwSubmitting.set(false);
        setTimeout(() => {
          this.auth.logout().subscribe(() => this.router.navigate(['/login']));
        }, 2500);
      },
      error: (err) => {
        this.pwSubmitting.set(false);
        this.pwError.set(
          err?.status === 401 || err?.status === 400
            ? 'Le mot de passe actuel est incorrect.'
            : 'Impossible de modifier le mot de passe pour le moment. Réessayez.',
        );
      },
    });
  }
}
