import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { forkJoin } from 'rxjs';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { MatiereResponse, RoleAssignment, UserResponse } from '../../core/api/models';
import { AuthStore } from '../../core/auth/auth.store';

/**
 * The « personnes » screen — ONE component, three route scopes (#259 / S5):
 *
 *   equipe/evaluateurs      responsable · directory of évaluateurs + create
 *   equipe/co-responsables  responsable · co-responsables of MY matière(s):
 *                           appoint an existing person or create an account
 *   admin/utilisateurs      super-admin · full directory + create + deactivate
 *
 * Scope comes from route data. All three views derive from ONE unfiltered
 * GET /users (the response carries each person's full role list), because the
 * views differ only in filtering and available actions — auth-service enforces
 * the real delegation matrix (UserService.validateDelegation) regardless.
 *
 * V1 deliberately has NO role-removal UI: the only removal endpoint is the
 * full-replace PUT /users/{id}/roles, where an incomplete payload silently
 * revokes roles — that flow needs its own carefully-designed pass.
 *
 * No email infrastructure exists, so account creation GENERATES a password and
 * shows it exactly once — the creator hands it to the person directly.
 */
@Component({
  selector: 'app-personnes',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './personnes.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PersonnesComponent {
  private readonly api = inject(DirectoryApiService);
  private readonly auth = inject(AuthStore);
  private readonly fb = inject(FormBuilder);

  readonly scope: 'evaluateurs' | 'co-responsables' | 'admin' =
    inject(ActivatedRoute).snapshot.data['scope'];

  // ---- data ----------------------------------------------------------------
  readonly users = signal<UserResponse[]>([]);
  readonly matieres = signal<MatiereResponse[]>([]);
  readonly loading = signal(true);
  readonly error = signal(false);

  readonly search = signal('');

  // ---- create form ----------------------------------------------------------
  readonly formOpen = signal(false);
  readonly submitting = signal(false);
  readonly submitError = signal<string | null>(null);
  /** Success banner: shows the generated password EXACTLY ONCE (no email infra). */
  readonly created = signal<{ nomComplet: string; email: string; password: string } | null>(null);

  readonly createForm = this.fb.nonNullable.group({
    prenom: ['', Validators.required],
    nom: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    // Mirrors the server policy (UserCreateRequest): min 8, 1 uppercase, 1 digit.
    password: ['', [Validators.required, Validators.minLength(8), Validators.pattern(/^(?=.*[A-Z])(?=.*\d).+$/)]],
    // co-responsables scope: which of MY matières the appointment targets.
    matiereId: [null as number | null],
    // admin scope: role picks.
    roleSuperAdmin: [false],
    roleResponsable: [false],
    respMatiereId: [null as number | null],
    roleEvaluateur: [false],
  });

  // ---- appoint an existing person (co-responsables scope) -------------------
  readonly appointOpen = signal(false);
  readonly appointUserId = signal<number | null>(null);
  readonly appointMatiereId = signal<number | null>(null);
  readonly appointSubmitting = signal(false);
  readonly appointError = signal<string | null>(null);

  // ---- retrait / rétablissement d'accès (admin scope) — #289 -----------------
  readonly confirmingDeactivation = signal<UserResponse | null>(null);
  readonly deactivating = signal(false);
  readonly deactivateError = signal<string | null>(null);
  /** #289 — motif obligatoire : fermer le compte d'un collègue s'explique. */
  readonly motifRetrait = signal('');
  /** Compte dont la réouverture est en cours de confirmation. */
  readonly confirmingReactivation = signal<UserResponse | null>(null);

  /**
   * Le champ motif du panneau ouvert (au plus un à la fois). `autofocus` ne
   * sert à rien ici : le navigateur ne l'honore qu'au chargement initial, pas
   * sur un bloc rendu par @if. On place donc le focus nous-mêmes.
   */
  private readonly motifInput = viewChild<ElementRef<HTMLTextAreaElement>>('motifInput');

  /**
   * Échap annule — posé sur le DOCUMENT, pas sur le panneau : au moment du
   * clic le focus est encore sur le bouton, qui est HORS du panneau, donc un
   * (keydown.escape) local ne recevait jamais l'évènement (constaté au test).
   */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.confirmingDeactivation() || this.confirmingReactivation()) {
      this.annulerRetrait();
    }
  }

  readonly myUserId = computed(() => this.auth.currentUser()?.userId ?? null);
  readonly myMatiereIds = computed(() => this.auth.responsableMatiereIds());

  constructor() {
    // Le panneau vient d'apparaître : y amener le clavier, sinon la personne
    // qui n'utilise pas la souris doit tabuler à l'aveugle jusqu'au motif.
    effect(() => {
      const champ = this.motifInput();
      if (champ) champ.nativeElement.focus();
    });
    this.load();
    if (this.scope === 'co-responsables') {
      // Single-matière responsable: preselect — the only possible answer.
      const ids = this.myMatiereIds();
      if (ids.length === 1) {
        this.createForm.controls.matiereId.setValue(ids[0]);
        this.appointMatiereId.set(ids[0]);
      }
    }
  }

  load(): void {
    this.loading.set(true);
    this.error.set(false);
    forkJoin({ users: this.api.listUsers(), matieres: this.api.listMatieres() }).subscribe({
      next: ({ users, matieres }) => {
        this.users.set(users);
        this.matieres.set(matieres);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  // ---- derived views ---------------------------------------------------------

  /** The rows this scope shows, before search. */
  private readonly scoped = computed<UserResponse[]>(() => {
    const all = this.users();
    switch (this.scope) {
      case 'evaluateurs':
        return all.filter((u) => u.roles?.some((r) => r.role === 'EVALUATEUR'));
      case 'co-responsables': {
        const mine = new Set(this.myMatiereIds());
        return all.filter((u) =>
          u.roles?.some((r) => r.role === 'RESPONSABLE_MATIERE' && r.matiereId != null && mine.has(r.matiereId)),
        );
      }
      case 'admin':
        return all;
    }
  });

  readonly rows = computed<UserResponse[]>(() => {
    const q = this.search().trim().toLowerCase();
    const base = [...this.scoped()].sort((a, b) =>
      `${a.nom} ${a.prenom}`.localeCompare(`${b.nom} ${b.prenom}`, 'fr'),
    );
    if (!q) return base;
    return base.filter((u) =>
      `${u.prenom} ${u.nom} ${u.email}`.toLowerCase().includes(q),
    );
  });

  /** co-responsables scope: people appointable for the selected matière. */
  readonly appointCandidates = computed<UserResponse[]>(() => {
    const mid = this.appointMatiereId();
    return this.users()
      .filter((u) => u.isActive)
      .filter((u) => !u.roles?.some((r) => r.role === 'RESPONSABLE_MATIERE' && r.matiereId === mid))
      // #216: a responsable cannot modify a SUPER_ADMIN account — don't offer it.
      .filter((u) => !u.roles?.some((r) => r.role === 'SUPER_ADMIN'))
      .sort((a, b) => `${a.nom} ${a.prenom}`.localeCompare(`${b.nom} ${b.prenom}`, 'fr'));
  });

  readonly myMatieres = computed<MatiereResponse[]>(() => {
    const mine = new Set(this.myMatiereIds());
    return this.matieres().filter((m) => mine.has(m.id));
  });

  // ---- labels -----------------------------------------------------------------

  readonly title = {
    evaluateurs: 'Évaluateurs',
    'co-responsables': 'Co-responsables',
    admin: 'Utilisateurs',
  }[this.scope];

  matiereLabel(id: number | null): string {
    if (id == null) return '';
    const m = this.matieres().find((x) => x.id === id);
    return m ? m.libelle : `Matière ${id}`;
  }

  roleChip(r: RoleAssignment): string {
    switch (r.role) {
      case 'SUPER_ADMIN':
        return 'Super-admin';
      case 'RESPONSABLE_MATIERE':
        return `Responsable — ${this.matiereLabel(r.matiereId)}`;
      case 'EVALUATEUR':
        return 'Évaluateur';
    }
  }

  frDate(iso: string | null): string {
    if (!iso) return '—';
    const [y, m, d] = iso.slice(0, 10).split('-');
    return y && m && d ? `${d}/${m}/${y}` : iso;
  }

  // ---- create -------------------------------------------------------------------

  openCreate(): void {
    this.submitError.set(null);
    this.created.set(null);
    this.createForm.controls.password.setValue(this.generatePassword());
    this.formOpen.set(true);
  }

  cancelCreate(): void {
    this.formOpen.set(false);
    this.createForm.reset({ matiereId: this.myMatiereIds().length === 1 ? this.myMatiereIds()[0] : null });
  }

  /**
   * 12 chars from an unambiguous alphabet, with the server policy guaranteed
   * (≥1 uppercase, ≥1 digit) by construction.
   */
  private generatePassword(): string {
    const upper = 'ABCDEFGHJKMNPQRSTUVWXYZ';
    const lower = 'abcdefghjkmnpqrstuvwxyz';
    const digits = '23456789';
    const all = upper + lower + digits;
    const pick = (s: string) => s[Math.floor(Math.random() * s.length)];
    const body = Array.from({ length: 9 }, () => pick(all));
    // Guaranteed classes, shuffled in at random positions.
    body.splice(Math.floor(Math.random() * body.length), 0, pick(upper));
    body.splice(Math.floor(Math.random() * body.length), 0, pick(digits));
    body.splice(Math.floor(Math.random() * body.length), 0, pick(lower));
    return body.join('');
  }

  regeneratePassword(): void {
    this.createForm.controls.password.setValue(this.generatePassword());
  }

  private rolesForCreate(): RoleAssignment[] | string {
    switch (this.scope) {
      case 'evaluateurs':
        return [{ role: 'EVALUATEUR', matiereId: null }];
      case 'co-responsables': {
        const mid = this.createForm.controls.matiereId.value;
        if (mid == null) return 'Choisissez la matière.';
        return [{ role: 'RESPONSABLE_MATIERE', matiereId: mid }];
      }
      case 'admin': {
        const c = this.createForm.controls;
        const roles: RoleAssignment[] = [];
        if (c.roleSuperAdmin.value) roles.push({ role: 'SUPER_ADMIN', matiereId: null });
        if (c.roleResponsable.value) {
          if (c.respMatiereId.value == null) return 'Choisissez la matière du responsable.';
          roles.push({ role: 'RESPONSABLE_MATIERE', matiereId: c.respMatiereId.value });
        }
        if (c.roleEvaluateur.value) roles.push({ role: 'EVALUATEUR', matiereId: null });
        if (roles.length === 0) return 'Choisissez au moins un rôle.';
        return roles;
      }
    }
  }

  submitCreate(): void {
    if (this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      return;
    }
    const roles = this.rolesForCreate();
    if (typeof roles === 'string') {
      this.submitError.set(roles);
      return;
    }
    const v = this.createForm.getRawValue();
    this.submitting.set(true);
    this.submitError.set(null);
    this.api
      .createUser({ email: v.email.trim(), password: v.password, nom: v.nom.trim(), prenom: v.prenom.trim(), roles })
      .subscribe({
        next: (u) => {
          this.submitting.set(false);
          this.formOpen.set(false);
          // The one and only display of the password — no email infra exists.
          this.created.set({ nomComplet: `${u.prenom} ${u.nom}`, email: u.email, password: v.password });
          this.createForm.reset({ matiereId: this.myMatiereIds().length === 1 ? this.myMatiereIds()[0] : null });
          this.load();
        },
        error: (e: HttpErrorResponse) => {
          this.submitting.set(false);
          this.submitError.set(
            e.error?.message ??
              (e.status === 409
                ? 'Un compte existe déjà avec cet e-mail — une personne = un compte, ajoutez-lui plutôt le rôle.'
                : 'La création a échoué. Réessayez.'),
          );
        },
      });
  }

  async copyPassword(): Promise<void> {
    const c = this.created();
    if (c) await navigator.clipboard.writeText(c.password);
  }

  // ---- appoint existing (co-responsables) -----------------------------------------

  openAppoint(): void {
    this.appointError.set(null);
    this.appointUserId.set(null);
    this.appointOpen.set(true);
  }

  onAppointUserChange(value: string): void {
    this.appointUserId.set(value ? Number(value) : null);
  }

  onAppointMatiereChange(value: string): void {
    this.appointMatiereId.set(value ? Number(value) : null);
  }

  submitAppoint(): void {
    const uid = this.appointUserId();
    const mid = this.appointMatiereId();
    if (uid == null || mid == null) {
      this.appointError.set('Choisissez la personne et la matière.');
      return;
    }
    this.appointSubmitting.set(true);
    this.appointError.set(null);
    this.api.addRoles(uid, [{ role: 'RESPONSABLE_MATIERE', matiereId: mid }]).subscribe({
      next: () => {
        this.appointSubmitting.set(false);
        this.appointOpen.set(false);
        this.load();
      },
      error: (e: HttpErrorResponse) => {
        this.appointSubmitting.set(false);
        this.appointError.set(e.error?.message ?? 'La nomination a échoué. Réessayez.');
      },
    });
  }

  // ---- deactivate (admin) -----------------------------------------------------------

  askDeactivate(u: UserResponse): void {
    this.deactivateError.set(null);
    this.motifRetrait.set('');
    this.confirmingReactivation.set(null);
    this.confirmingDeactivation.set(u);
  }

  askReactivate(u: UserResponse): void {
    this.deactivateError.set(null);
    this.motifRetrait.set('');
    this.confirmingDeactivation.set(null);
    this.confirmingReactivation.set(u);
  }

  annulerRetrait(): void {
    this.confirmingDeactivation.set(null);
    this.confirmingReactivation.set(null);
  }

  confirmDeactivate(): void {
    const u = this.confirmingDeactivation();
    if (!u) return;
    const motif = this.motifRetrait().trim();
    if (!motif) {
      this.deactivateError.set(
        'Le motif est obligatoire : un retrait d’accès doit pouvoir s’expliquer.',
      );
      return;
    }
    this.deactivating.set(true);
    this.deactivateError.set(null);
    this.api.deactivateUser(u.id, motif).subscribe({
      next: () => {
        this.deactivating.set(false);
        this.confirmingDeactivation.set(null);
        this.load();
      },
      error: (e: HttpErrorResponse) => {
        this.deactivating.set(false);
        // Les refus du serveur (soi-même, dernier admin) sont nominatifs :
        // on les affiche mot pour mot plutôt que d'inventer un texte générique.
        this.deactivateError.set(e.error?.message ?? 'Le retrait a échoué. Réessayez.');
      },
    });
  }

  confirmReactivate(): void {
    const u = this.confirmingReactivation();
    if (!u) return;
    const motif = this.motifRetrait().trim();
    if (!motif) {
      this.deactivateError.set('Indiquez pourquoi ce compte est rouvert.');
      return;
    }
    this.deactivating.set(true);
    this.deactivateError.set(null);
    this.api.reactivateUser(u.id, motif).subscribe({
      next: () => {
        this.deactivating.set(false);
        this.confirmingReactivation.set(null);
        this.load();
      },
      error: (e: HttpErrorResponse) => {
        this.deactivating.set(false);
        this.deactivateError.set(e.error?.message ?? 'La réouverture a échoué. Réessayez.');
      },
    });
  }

  /** #289 — « Retiré le 04/08/2026 par Aymen Ben Ali — motif », lisible longtemps après. */
  retraitLabel(u: UserResponse): string {
    if (!u.deactivatedAt) return 'Retiré (avant la traçabilité — motif inconnu)';
    const auteur = u.deactivatedBy != null
      ? this.users().find((x) => x.id === u.deactivatedBy)
      : null;
    const par = auteur ? ` par ${auteur.prenom} ${auteur.nom}` : '';
    const motif = u.deactivationMotif ? ` — ${u.deactivationMotif}` : '';
    return `Retiré le ${this.frDate(u.deactivatedAt)}${par}${motif}`;
  }
}
