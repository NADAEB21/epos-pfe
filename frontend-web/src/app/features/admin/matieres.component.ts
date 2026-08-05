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
import { HttpErrorResponse } from '@angular/common/http';
import { forkJoin } from 'rxjs';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import {
  MatiereImportResult,
  MatiereImportRow,
  MatiereResponse,
  UserResponse,
} from '../../core/api/models';

/**
 * #134 — le catalogue des matières (admin/matieres, SUPER_ADMIN seul).
 *
 * Dernier écran qui obligeait à faire un INSERT SQL : sans lui, une
 * installation neuve ne peut pas démarrer une matière. Verbes : créer,
 * renommer, retirer, rouvrir, importer une liste. JAMAIS de suppression —
 * matiere_id traverse les services en clé logique sans contrainte SQL
 * (ADR-0006) : un DELETE orphelinerait les examens passés, dont les
 * résultats afficheraient « Matière 7 » au lieu d'un nom.
 *
 * Le retrait suit la doctrine du retrait d'un compte (#289) : motivé,
 * attribué, réversible, et la provenance reste lisible sur la ligne.
 */
@Component({
  selector: 'app-matieres',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './matieres.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MatieresComponent {
  private readonly api = inject(DirectoryApiService);
  private readonly fb = inject(FormBuilder);

  // ---- data ----------------------------------------------------------------
  readonly matieres = signal<MatiereResponse[]>([]);
  /** Annuaire — uniquement pour résoudre « retirée par QUI » en nom lisible. */
  readonly users = signal<UserResponse[]>([]);
  readonly loading = signal(true);
  readonly error = signal(false);

  readonly search = signal('');

  // ---- create / rename panel (one form, editingId decides) -------------------
  readonly formOpen = signal(false);
  /** null = création ; sinon la matière en cours de renommage. */
  readonly editing = signal<MatiereResponse | null>(null);
  readonly submitting = signal(false);
  readonly submitError = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.maxLength(20)]],
    libelle: ['', [Validators.required, Validators.maxLength(100)]],
  });

  // ---- retrait / réouverture — même doctrine que #289 -------------------------
  readonly confirmingRetrait = signal<MatiereResponse | null>(null);
  readonly confirmingReactivation = signal<MatiereResponse | null>(null);
  readonly motif = signal('');
  readonly acting = signal(false);
  readonly actError = signal<string | null>(null);

  // ---- import en lot ---------------------------------------------------------
  readonly importOpen = signal(false);
  readonly importText = signal('');
  readonly importSubmitting = signal(false);
  readonly importError = signal<string | null>(null);
  readonly importResult = signal<MatiereImportResult | null>(null);

  /**
   * Le champ motif du panneau ouvert (au plus un à la fois). `autofocus` ne
   * sert à rien sur un bloc rendu par @if — le focus se pose à la main
   * (leçon #289).
   */
  private readonly motifInput = viewChild<ElementRef<HTMLTextAreaElement>>('motifInput');

  /**
   * Échap annule — sur le DOCUMENT : au moment du clic le focus est encore
   * sur le bouton déclencheur, hors du panneau (leçon #289).
   */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.confirmingRetrait() || this.confirmingReactivation()) {
      this.annuler();
    }
  }

  constructor() {
    effect(() => {
      const champ = this.motifInput();
      if (champ) champ.nativeElement.focus();
    });
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(false);
    forkJoin({ matieres: this.api.listMatieres(), users: this.api.listUsers() }).subscribe({
      next: ({ matieres, users }) => {
        this.matieres.set(matieres);
        this.users.set(users);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  // ---- derived views ---------------------------------------------------------

  readonly rows = computed<MatiereResponse[]>(() => {
    const q = this.search().trim().toLowerCase();
    // Actives d'abord, puis alphabétique — une matière retirée est une
    // information d'archive, pas la première chose à lire.
    const base = [...this.matieres()].sort(
      (a, b) =>
        Number(b.active) - Number(a.active) || a.libelle.localeCompare(b.libelle, 'fr'),
    );
    if (!q) return base;
    return base.filter((m) => `${m.code} ${m.libelle}`.toLowerCase().includes(q));
  });

  readonly nbRetirees = computed(() => this.matieres().filter((m) => !m.active).length);

  // ---- labels -----------------------------------------------------------------

  frDate(iso: string | null | undefined): string {
    if (!iso) return '—';
    const [y, m, d] = iso.slice(0, 10).split('-');
    return y && m && d ? `${d}/${m}/${y}` : iso;
  }

  /** « Retirée le 06/08/2026 par Aymen Ben Ali — motif », lisible longtemps après. */
  retraitLabel(m: MatiereResponse): string {
    if (!m.retiredAt) return 'Retirée';
    const auteur = m.retiredBy != null ? this.users().find((u) => u.id === m.retiredBy) : null;
    const par = auteur ? ` par ${auteur.prenom} ${auteur.nom}` : '';
    const motif = m.retirementMotif ? ` — ${m.retirementMotif}` : '';
    return `Retirée le ${this.frDate(m.retiredAt)}${par}${motif}`;
  }

  // ---- create / rename --------------------------------------------------------

  openCreate(): void {
    this.submitError.set(null);
    this.editing.set(null);
    this.form.reset();
    this.importOpen.set(false);
    this.formOpen.set(true);
  }

  openRename(m: MatiereResponse): void {
    this.submitError.set(null);
    this.editing.set(m);
    this.form.setValue({ code: m.code, libelle: m.libelle });
    this.importOpen.set(false);
    this.formOpen.set(true);
  }

  cancelForm(): void {
    this.formOpen.set(false);
    this.editing.set(null);
    this.form.reset();
  }

  submitForm(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const body = { code: v.code.trim(), libelle: v.libelle.trim() };
    const editing = this.editing();
    this.submitting.set(true);
    this.submitError.set(null);
    const call = editing ? this.api.updateMatiere(editing.id, body) : this.api.createMatiere(body);
    call.subscribe({
      next: () => {
        this.submitting.set(false);
        this.cancelForm();
        this.load();
      },
      error: (e: HttpErrorResponse) => {
        this.submitting.set(false);
        // Les refus du serveur (code déjà pris, y compris par une matière
        // retirée) sont nominatifs : affichés mot pour mot.
        this.submitError.set(
          e.error?.message ??
            (e.status === 409
              ? 'Ce code est déjà pris par une autre matière.'
              : "L'enregistrement a échoué. Réessayez."),
        );
      },
    });
  }

  // ---- retrait / réouverture ---------------------------------------------------

  askRetirer(m: MatiereResponse): void {
    this.actError.set(null);
    this.motif.set('');
    this.confirmingReactivation.set(null);
    this.confirmingRetrait.set(m);
  }

  askReactiver(m: MatiereResponse): void {
    this.actError.set(null);
    this.motif.set('');
    this.confirmingRetrait.set(null);
    this.confirmingReactivation.set(m);
  }

  annuler(): void {
    this.confirmingRetrait.set(null);
    this.confirmingReactivation.set(null);
  }

  confirmRetirer(): void {
    const m = this.confirmingRetrait();
    if (!m) return;
    const motif = this.motif().trim();
    if (!motif) {
      this.actError.set('Le motif est obligatoire : fermer une matière doit pouvoir s’expliquer.');
      return;
    }
    this.acting.set(true);
    this.actError.set(null);
    this.api.retirerMatiere(m.id, motif).subscribe({
      next: () => {
        this.acting.set(false);
        this.confirmingRetrait.set(null);
        this.load();
      },
      error: (e: HttpErrorResponse) => {
        this.acting.set(false);
        this.actError.set(e.error?.message ?? 'Le retrait a échoué. Réessayez.');
      },
    });
  }

  confirmReactiver(): void {
    const m = this.confirmingReactivation();
    if (!m) return;
    const motif = this.motif().trim();
    if (!motif) {
      this.actError.set('Indiquez pourquoi cette matière est rouverte.');
      return;
    }
    this.acting.set(true);
    this.actError.set(null);
    this.api.reactiverMatiere(m.id, motif).subscribe({
      next: () => {
        this.acting.set(false);
        this.confirmingReactivation.set(null);
        this.load();
      },
      error: (e: HttpErrorResponse) => {
        this.acting.set(false);
        this.actError.set(e.error?.message ?? 'La réouverture a échoué. Réessayez.');
      },
    });
  }

  // ---- import en lot -----------------------------------------------------------

  openImport(): void {
    this.importError.set(null);
    this.importResult.set(null);
    this.importText.set('');
    this.formOpen.set(false);
    this.importOpen.set(true);
  }

  cancelImport(): void {
    this.importOpen.set(false);
    this.importResult.set(null);
  }

  /**
   * Une ligne = une matière : « CODE ; Libellé » — le point-virgule OU la
   * tabulation (copier-coller direct depuis Excel). Une ligne sans séparateur
   * part avec un code vide : c'est le serveur qui rend le verdict par ligne,
   * le client ne préjuge pas.
   */
  parseImportText(text: string): MatiereImportRow[] {
    return text
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter((l) => l.length > 0)
      .map((line) => {
        const sep = line.includes('\t') ? '\t' : ';';
        const idx = line.indexOf(sep);
        if (idx < 0) return { code: '', libelle: line.trim() };
        return {
          code: line.slice(0, idx).trim(),
          libelle: line.slice(idx + 1).trim(),
        };
      });
  }

  submitImport(): void {
    const rows = this.parseImportText(this.importText());
    if (rows.length === 0) {
      this.importError.set('Collez au moins une ligne « CODE ; Libellé ».');
      return;
    }
    this.importSubmitting.set(true);
    this.importError.set(null);
    this.api.importMatieres(rows).subscribe({
      next: (result) => {
        this.importSubmitting.set(false);
        this.importResult.set(result);
        this.load();
      },
      error: (e: HttpErrorResponse) => {
        this.importSubmitting.set(false);
        this.importError.set(e.error?.message ?? "L'import a échoué. Réessayez.");
      },
    });
  }

  statutLabel(statut: 'CREATED' | 'DUPLICATE' | 'ERROR'): string {
    switch (statut) {
      case 'CREATED':
        return 'Créée';
      case 'DUPLICATE':
        return 'Doublon';
      case 'ERROR':
        return 'Erreur';
    }
  }
}
