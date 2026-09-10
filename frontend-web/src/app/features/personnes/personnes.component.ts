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
import { InvitationStatus, MatiereResponse, RoleAssignment, UserResponse } from '../../core/api/models';
import { RoleType } from '../../core/auth/auth.models';
import { AuthStore } from '../../core/auth/auth.store';

/**
 * The « personnes » screen — ONE component, three route scopes (#259 / S5):
 *
 *   equipe/evaluateurs      responsable · directory of évaluateurs + create,
 *                           or appoint an existing person évaluateur (#436)
 *   equipe/co-responsables  responsable · co-responsables of MY matière(s):
 *                           appoint an existing person or create an account
 *   admin/utilisateurs      super-admin · full directory + create + deactivate
 *                           + add a role to an existing account (#436),
 *                           filters by role / matière / état (#437)
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
 * #389 — account creation sends an INVITATION e-mail (« choisissez votre mot de
 * passe », link valid 7 days, single use) from the system sender. The web never
 * generates nor displays a password any more. When the mailer is disabled
 * (`app.mail.enabled=false`) the server says so (`invitation.simulee`) and the
 * screen shows it honestly instead of a green « envoyée » — the creator can
 * « Renvoyer l'invitation » once the mailer is on.
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
  /** #389 — success banner: what happened to the invitation e-mail (never a password). */
  readonly created = signal<{ nomComplet: string; email: string; invitation: InvitationStatus | null } | null>(null);

  /** #389 — resend feedback, per row. */
  readonly renvoiEnCours = signal<number | null>(null);
  readonly renvoiResultat = signal<{ userId: number; statut: InvitationStatus } | null>(null);
  readonly renvoiErreur = signal<{ userId: number; message: string } | null>(null);

  readonly createForm = this.fb.nonNullable.group({
    prenom: ['', Validators.required],
    nom: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    // co-responsables scope: which of MY matières the appointment targets.
    matiereId: [null as number | null],
    // admin scope: role picks.
    roleSuperAdmin: [false],
    roleResponsable: [false],
    respMatiereId: [null as number | null],
    roleEvaluateur: [false],
  });

  // ---- appoint an existing person (évaluateurs + co-responsables) — #436 ------
  readonly appointOpen = signal(false);
  readonly appointUserId = signal<number | null>(null);
  readonly appointMatiereId = signal<number | null>(null);
  readonly appointSubmitting = signal(false);
  readonly appointError = signal<string | null>(null);
  /**
   * #436 — le rôle que « Nommer une personne existante » ajoute, selon la portée. Le
   * serveur (POST /users/{id}/roles, additif) savait déjà tout faire ; seul l'écran des
   * évaluateurs n'offrait que « créer un compte » — et répondait 409 « ajoutez-lui plutôt
   * le rôle » sans aucun bouton pour le faire.
   */
  readonly appointRole = computed<RoleType | null>(() =>
    this.scope === 'evaluateurs' ? 'EVALUATEUR'
      : this.scope === 'co-responsables' ? 'RESPONSABLE_MATIERE'
        : null,
  );

  // ---- #436 admin : ajouter un rôle à un compte existant, ligne par ligne ---------
  readonly roleAddUserId = signal<number | null>(null);
  readonly roleAddSubmitting = signal(false);
  readonly roleAddError = signal<string | null>(null);
  readonly roleAddForm = this.fb.nonNullable.group({
    evaluateur: [false],
    responsable: [false],
    respMatiereId: [null as number | null],
    superAdmin: [false],
  });

  // ---- #437 filtres ------------------------------------------------------------
  readonly roleFilter = signal<'all' | RoleType>('all');
  readonly matiereFilter = signal<number | null>(null);
  /** Actifs par défaut : un annuaire de travail ne montre pas les comptes fermés sans qu'on le demande. */
  readonly etatFilter = signal<'actifs' | 'retires' | 'tous'>('actifs');

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
    if (this.roleAddUserId() != null) this.closeRoleAdd();
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

  /** #437 — les lignes affichées : portée, puis état, rôle, matière, recherche. */
  readonly rows = computed<UserResponse[]>(() => {
    const q = this.search().trim().toLowerCase();
    const role = this.roleFilter();
    const mid = this.matiereFilter();
    const etat = this.etatFilter();
    const base = [...this.scoped()].sort((a, b) =>
      `${a.nom} ${a.prenom}`.localeCompare(`${b.nom} ${b.prenom}`, 'fr'),
    );
    return base.filter((u) => {
      if (etat === 'actifs' && !u.isActive) return false;
      if (etat === 'retires' && u.isActive) return false;
      if (role !== 'all' && !u.roles?.some((r) => r.role === role)) return false;
      if (mid != null && !u.roles?.some((r) => r.role === 'RESPONSABLE_MATIERE' && r.matiereId === mid)) return false;
      if (q && !`${u.prenom} ${u.nom} ${u.email}`.toLowerCase().includes(q)) return false;
      return true;
    });
  });

  /** #437 — « N affiché(s) sur M » : le total de la portée, avant filtres. */
  readonly totalScoped = computed(() => this.scoped().length);
  readonly filtresActifs = computed(
    () => this.roleFilter() !== 'all' || this.matiereFilter() != null || this.etatFilter() !== 'actifs' || !!this.search().trim(),
  );

  reinitialiserFiltres(): void {
    this.roleFilter.set('all');
    this.matiereFilter.set(null);
    this.etatFilter.set('actifs');
    this.search.set('');
  }

  onRoleFilterChange(value: string): void {
    this.roleFilter.set((value || 'all') as 'all' | RoleType);
    if (this.roleFilter() !== 'RESPONSABLE_MATIERE') this.matiereFilter.set(null);
  }

  onMatiereFilterChange(value: string): void {
    this.matiereFilter.set(value ? Number(value) : null);
  }

  onEtatFilterChange(value: string): void {
    this.etatFilter.set((value || 'actifs') as 'actifs' | 'retires' | 'tous');
  }

  /**
   * People appointable in this scope (#436) :
   *  - évaluateurs : actifs sans le rôle EVALUATEUR ;
   *  - co-responsables : actifs pas déjà responsables de la matière choisie.
   * Un compte SUPER_ADMIN n'est jamais proposé à un responsable (#216 : il ne peut pas le
   * modifier) — l'admin, lui, ajoute les rôles depuis son propre écran, ligne par ligne.
   */
  readonly appointCandidates = computed<UserResponse[]>(() => {
    const mid = this.appointMatiereId();
    const role = this.appointRole();
    return this.users()
      .filter((u) => u.isActive)
      .filter((u) => role !== 'EVALUATEUR' || !u.roles?.some((r) => r.role === 'EVALUATEUR'))
      .filter((u) => role !== 'RESPONSABLE_MATIERE'
        || !u.roles?.some((r) => r.role === 'RESPONSABLE_MATIERE' && r.matiereId === mid))
      .filter((u) => this.auth.isSuperAdmin() || !u.roles?.some((r) => r.role === 'SUPER_ADMIN'))
      .sort((a, b) => `${a.nom} ${a.prenom}`.localeCompare(`${b.nom} ${b.prenom}`, 'fr'));
  });

  /**
   * #134 — matières proposables pour une NOMINATION : les retirées sont
   * exclues (le serveur refuse de toute façon). `matieres()` reste complète
   * pour `matiereLabel` — un rôle historique sur une matière retirée doit
   * garder son libellé.
   */
  readonly matieresActives = computed<MatiereResponse[]>(() =>
    this.matieres().filter((m) => m.active),
  );

  readonly myMatieres = computed<MatiereResponse[]>(() => {
    const mine = new Set(this.myMatiereIds());
    return this.matieresActives().filter((m) => mine.has(m.id));
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
    this.formOpen.set(true);
  }

  cancelCreate(): void {
    this.formOpen.set(false);
    this.createForm.reset({ matiereId: this.myMatiereIds().length === 1 ? this.myMatiereIds()[0] : null });
  }

  /**
   * #389 — the three honest readings of an invitation status. `simulee` wins:
   * a stub that « sent » into the void is not a sent e-mail.
   */
  invitationEtat(s: InvitationStatus | null | undefined): 'envoyee' | 'simulee' | 'echec' | 'inconnu' {
    if (!s) return 'inconnu';
    if (s.simulee) return 'simulee';
    return s.envoyee ? 'envoyee' : 'echec';
  }

  /** #389 — POST /users/{id}/invitation : lien 7 jours réémis, e-mail renvoyé. */
  renvoyerInvitation(u: UserResponse): void {
    if (this.renvoiEnCours() !== null) return;
    this.renvoiEnCours.set(u.id);
    this.renvoiResultat.set(null);
    this.renvoiErreur.set(null);
    this.api.resendInvitation(u.id).subscribe({
      next: (statut) => {
        this.renvoiEnCours.set(null);
        this.renvoiResultat.set({ userId: u.id, statut });
      },
      error: (e: HttpErrorResponse) => {
        this.renvoiEnCours.set(null);
        this.renvoiErreur.set({
          userId: u.id,
          message:
            e.error?.message ??
            (e.status === 403
              ? "Hors de votre périmètre : vous ne pouvez pas renvoyer l'invitation de cette personne."
              : "Le renvoi a échoué. Réessayez."),
        });
      },
    });
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
      // #389 — no password: the server issues the invitation link.
      .createUser({ email: v.email.trim(), nom: v.nom.trim(), prenom: v.prenom.trim(), roles })
      .subscribe({
        next: (u) => {
          this.submitting.set(false);
          this.formOpen.set(false);
          this.created.set({ nomComplet: `${u.prenom} ${u.nom}`, email: u.email, invitation: u.invitation ?? null });
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
    const role = this.appointRole();
    if (role == null) return;
    if (uid == null || (role === 'RESPONSABLE_MATIERE' && mid == null)) {
      this.appointError.set(role === 'RESPONSABLE_MATIERE'
        ? 'Choisissez la personne et la matière.'
        : 'Choisissez la personne.');
      return;
    }
    const roles: RoleAssignment[] = role === 'RESPONSABLE_MATIERE'
      ? [{ role: 'RESPONSABLE_MATIERE', matiereId: mid }]
      : [{ role: 'EVALUATEUR', matiereId: null }];
    this.appointSubmitting.set(true);
    this.appointError.set(null);
    this.api.addRoles(uid, roles).subscribe({
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

  // ---- #436 admin : ajouter un rôle à un compte existant ------------------------------

  hasRole(u: UserResponse, role: RoleType, matiereId: number | null = null): boolean {
    return !!u.roles?.some((r) => r.role === role && (role !== 'RESPONSABLE_MATIERE' || r.matiereId === matiereId));
  }

  /** Les matières dont ce compte n'est PAS encore responsable (actives seulement, #134). */
  matieresAjoutables(u: UserResponse): MatiereResponse[] {
    return this.matieresActives().filter((m) => !this.hasRole(u, 'RESPONSABLE_MATIERE', m.id));
  }

  openRoleAdd(u: UserResponse): void {
    this.roleAddError.set(null);
    this.roleAddForm.reset({ evaluateur: false, responsable: false, respMatiereId: null, superAdmin: false });
    // Les rôles déjà portés se grisent PAR LE CONTRÔLE : un [attr.disabled] sur un
    // formControlName est écrasé par la directive de formulaire (constaté au navigateur).
    const c = this.roleAddForm.controls;
    this.hasRole(u, 'EVALUATEUR') ? c.evaluateur.disable() : c.evaluateur.enable();
    this.matieresAjoutables(u).length === 0 ? c.responsable.disable() : c.responsable.enable();
    this.hasRole(u, 'SUPER_ADMIN') ? c.superAdmin.disable() : c.superAdmin.enable();
    this.roleAddUserId.set(u.id);
  }

  closeRoleAdd(): void {
    this.roleAddUserId.set(null);
    this.roleAddError.set(null);
  }

  /** Les rôles cochés qui ne sont pas déjà portés — ce que le POST additif ajoutera. */
  rolesToAdd(u: UserResponse): RoleAssignment[] {
    const c = this.roleAddForm.controls;
    const roles: RoleAssignment[] = [];
    if (c.evaluateur.value && !this.hasRole(u, 'EVALUATEUR')) roles.push({ role: 'EVALUATEUR', matiereId: null });
    if (c.responsable.value && c.respMatiereId.value != null && !this.hasRole(u, 'RESPONSABLE_MATIERE', c.respMatiereId.value)) {
      roles.push({ role: 'RESPONSABLE_MATIERE', matiereId: c.respMatiereId.value });
    }
    if (c.superAdmin.value && !this.hasRole(u, 'SUPER_ADMIN')) roles.push({ role: 'SUPER_ADMIN', matiereId: null });
    return roles;
  }

  submitRoleAdd(u: UserResponse): void {
    const c = this.roleAddForm.controls;
    if (c.responsable.value && c.respMatiereId.value == null) {
      this.roleAddError.set('Choisissez la matière du responsable.');
      return;
    }
    const roles = this.rolesToAdd(u);
    if (roles.length === 0) {
      this.roleAddError.set('Cochez au moins un rôle que ce compte ne porte pas déjà.');
      return;
    }
    this.roleAddSubmitting.set(true);
    this.roleAddError.set(null);
    this.api.addRoles(u.id, roles).subscribe({
      next: () => {
        this.roleAddSubmitting.set(false);
        this.closeRoleAdd();
        this.load();
      },
      error: (e: HttpErrorResponse) => {
        this.roleAddSubmitting.set(false);
        // Les refus du serveur (délégation, matière retirée) sont nominatifs : mot pour mot.
        this.roleAddError.set(e.error?.message ?? "L'ajout du rôle a échoué. Réessayez.");
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
