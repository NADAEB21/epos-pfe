import { Component, computed, effect, inject, signal } from '@angular/core';
import { input } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { forkJoin } from 'rxjs';
import * as XLSX from 'xlsx';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import {
  EtudiantSummary,
  ImportEtudiantRow,
  ImportResult,
  LotSummary,
  ParticipationSummary,
} from '../../../core/api/models';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';

/** A participation joined to its student — one roster row. */
interface RosterRow {
  participationId: number;
  etudiantId: number | null;
  nom: string;
  prenom: string;
  numeroInscription: string | null;
  email: string | null; // #227 Added
  /** #256 — position in the imported sheet; null for hand-added students. */
  ordreImport: number | null;
  numEchantillon: string | null;
  present: boolean | null;
  note: number | null;
  lotId: number | null;
}

/** One parsed row of an upload, with client-side validation, before POST. */
interface ImportDraftRow {
  ligne: number;
  nom: string;
  prenom: string;
  numero_inscription: string;
  email: string; // #227 Added
  valid: boolean;
  issue: string | null;
}

type PresenceFilter = 'all' | 'present' | 'absent' | 'unmarked';

/** Normalise a numéro d'inscription for duplicate comparison (trim + casefold). */
function normNumero(v: string | null | undefined): string {
  return (v ?? '').trim().toLowerCase();
}

/** Normalise a column header for matching: strip accents, lowercase, drop non-alnum. */
function normHeader(h: unknown): string {
  return (h ?? '')
    .toString()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]/g, '');
}

/** Accepted header spellings for the numéro d'inscription column. */
const NUM_HEADERS = new Set([
  'numeroinscription',
  'numerodinscription',
  'numero',
  'num',
  'ninscription',
  'numinscription',
  'matricule',
  'inscription',
  'cin',
]);

/** #227 Accepted header spellings for email. */
const EMAIL_HEADERS = new Set(['email', 'mail', 'courriel', 'adresseemail', 'contact']);

/**
 * #256 — the roster is shown in the ORDER OF THE IMPORTED SHEET, never
 * alphabetically. The supervisor ruled the file's row order is the official
 * listing order, and the « # » column here reads as a position: sorting by name
 * while numbering 1..N told the teacher that D227-08 was student #1. The
 * convocations screen already orders this way, so this is also what keeps the
 * two views telling the same story.
 *
 * Hand-added students carry no position and sort LAST, matching
 * LotAssignmentService's own rule (a bare `?? 0` would put them first).
 */
function compareListing(
  a: { ordreImport: number | null; nom: string; prenom: string },
  b: { ordreImport: number | null; nom: string; prenom: string },
): number {
  const ra = a.ordreImport ?? Number.MAX_SAFE_INTEGER;
  const rb = b.ordreImport ?? Number.MAX_SAFE_INTEGER;
  return (
    ra - rb ||
    (a.nom || '').localeCompare(b.nom || '') ||
    (a.prenom || '').localeCompare(b.prenom || '')
  );
}

@Component({
  selector: 'app-etudiants',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './etudiants.component.html',
})
export class EtudiantsComponent {
  private readonly scoring = inject(ScoringApiService);
  private readonly store = inject(ExamenWorkspaceStore);
  private readonly fb = inject(FormBuilder);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  readonly rows = signal<RosterRow[]>([]);
  /** Full student directory, kept to resolve names + guard duplicate numéros. */
  readonly directory = signal<EtudiantSummary[]>([]);
  /** Exam lots, to label the lot filter (lotId → numéro) and list the options. */
  readonly lots = signal<LotSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal(false);

  // ---- roster filters (text / présence / lot) -----------------------------
  readonly rosterSearch = signal('');
  readonly presenceFilter = signal<PresenceFilter>('all');
  /** 'all' | 'none' (non assigné) | a lot id as string. */
  readonly lotFilter = signal<string>('all');

  private readonly lotNumById = computed(() => {
    const m = new Map<number, number>();
    for (const l of this.lots()) if (l.numeroLot != null) m.set(l.id, l.numeroLot);
    return m;
  });

  readonly lotOptions = computed(() =>
    [...this.lots()]
      .sort((a, b) => (a.numeroLot ?? 0) - (b.numeroLot ?? 0))
      .map((l) => ({ id: l.id, numeroLot: l.numeroLot ?? 0 })),
  );

  /** Any participation not yet assigned to a lot — drives the "non assigné" option. */
  readonly hasUnassigned = computed(() => this.rows().some((r) => r.lotId == null));

  readonly filteredRows = computed(() => {
    const q = this.rosterSearch().trim().toLowerCase();
    const pres = this.presenceFilter();
    const lot = this.lotFilter();
    return this.rows().filter((r) => {
      if (q && !`${r.prenom} ${r.nom} ${r.numeroInscription ?? ''} ${r.email ?? ''}`.toLowerCase().includes(q)) {
        return false;
      }
      if (pres === 'present' && r.present !== true) return false;
      if (pres === 'absent' && r.present !== false) return false;
      if (pres === 'unmarked' && r.present != null) return false;
      if (lot === 'none' && r.lotId != null) return false;
      if (lot !== 'all' && lot !== 'none' && r.lotId !== Number(lot)) return false;
      return true;
    });
  });

  lotLabel(lotId: number | null): string {
    if (lotId == null) return '—';
    const n = this.lotNumById().get(lotId);
    return n != null ? `Lot ${n}` : `Lot #${lotId}`;
  }

  /** Roster authoring is allowed only while the exam is BROUILLON or CONFIGURE. */
  readonly editable = computed(() => {
    const e = this.store.exam();
    return e ? e.statut === 'BROUILLON' || e.statut === 'CONFIGURE' : false;
  });

  /**
   * Présence + note are day-of facts (set via marquerPresence / mobile scoring
   * once the exam runs). They are meaningless during authoring, so we only
   * surface them from EN_COURS onward — otherwise "À venir" exams misleadingly
   * advertise presence/notes that haven't happened yet.
   */
  readonly showDayOf = computed(() => {
    const s = this.store.exam()?.statut;
    return s === 'EN_COURS' || s === 'TERMINE' || s === 'ARCHIVE';
  });

  /** Visible column count, for the "no match" row's colspan. */
  readonly colspan = computed(
    () => 6 + (this.showDayOf() ? 2 : 0) + (this.editable() ? 1 : 0),
  );

  readonly presentsCount = computed(() => this.rows().filter((r) => r.present === true).length);
  readonly absentsCount = computed(() => this.rows().filter((r) => r.present === false).length);

  // ---- authoring UI state -------------------------------------------------
  readonly mode = signal<'idle' | 'new' | 'existing' | 'import'>('idle');
  readonly submitting = signal(false);
  readonly enrollingId = signal<number | null>(null);
  readonly addError = signal<string | null>(null);
  readonly search = signal('');

  readonly confirmRemoveId = signal<number | null>(null);

  // ---- #227 inline e-mail edit ---------------------------------------------
  /** etudiantId whose address is being edited (keyed on the STUDENT, not the
   *  participation: the address belongs to the directory record). */
  readonly emailEditId = signal<number | null>(null);
  readonly emailDraft = signal('');
  readonly savingEmailId = signal<number | null>(null);
  readonly emailError = signal<string | null>(null);
  readonly removingId = signal<number | null>(null);
  readonly removeError = signal<string | null>(null);

  // ---- bulk import (CSV / Excel) state ------------------------------------
  readonly importFileName = signal<string>('');
  /** Parsed + client-validated rows, shown for review before POST. */
  readonly importPreview = signal<ImportDraftRow[] | null>(null);
  /** Per-row outcome returned by the backend, shown after POST. */
  readonly importResult = signal<ImportResult | null>(null);
  readonly importing = signal(false);
  readonly importError = signal<string | null>(null);

  readonly importValidCount = computed(
    () => this.importPreview()?.filter((r) => r.valid).length ?? 0,
  );
  readonly importInvalidCount = computed(
    () => this.importPreview()?.filter((r) => !r.valid).length ?? 0,
  );

  readonly newForm = this.fb.nonNullable.group({
    prenom: ['', [Validators.required, Validators.maxLength(100)]],
    nom: ['', [Validators.required, Validators.maxLength(100)]],
    numeroInscription: ['', [Validators.required, Validators.maxLength(50)]],
    email: ['', [Validators.email, Validators.maxLength(255)]], // #227 Added
  });

  /** etudiantIds already on this exam's roster, for the existing-student filter. */
  private readonly enrolledIds = computed(
    () => new Set(this.rows().map((r) => r.etudiantId).filter((x): x is number => x != null)),
  );

  /** Directory students not yet enrolled — the pool the existing-picker draws from. */
  readonly availableStudents = computed(() => {
    const enrolled = this.enrolledIds();
    return this.directory()
      .filter((e) => !enrolled.has(e.id))
      .sort((a, b) => (a.nom ?? '').localeCompare(b.nom ?? '') || (a.prenom ?? '').localeCompare(b.prenom ?? ''));
  });

  readonly filteredAvailable = computed(() => {
    const q = this.search().trim().toLowerCase();
    if (!q) return this.availableStudents();
    return this.availableStudents().filter((e) =>
      `${e.prenom ?? ''} ${e.nom ?? ''} ${e.numero_inscription ?? ''}`.toLowerCase().includes(q),
    );
  });

  constructor() {
    effect(() => {
      const examId = Number(this.id());
      if (!Number.isFinite(examId)) {
        this.error.set(true);
        this.loading.set(false);
        return;
      }
      this.load(examId);
    }, { allowSignalWrites: true });
  }

  reload(): void {
    this.load(Number(this.id()));
  }

  private load(examId: number): void {
    this.loading.set(true);
    this.error.set(false);
    this.mode.set('idle');
    forkJoin({
      participations: this.scoring.listParticipations(examId),
      etudiants: this.scoring.listEtudiants(),
      lots: this.scoring.listLots(examId),
    }).subscribe({
      next: ({ participations, etudiants, lots }) => {
        this.directory.set(etudiants);
        this.lots.set(lots);
        this.rows.set(this.buildRows(participations, etudiants));
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  /** Join participations (enrolments) to the student directory, ordered by name. */
  private buildRows(
    participations: ParticipationSummary[],
    etudiants: EtudiantSummary[],
  ): RosterRow[] {
    const byId = new Map<number, EtudiantSummary>();
    for (const e of etudiants) byId.set(e.id, e);

    return participations
      .map((p) => {
        const e = p.etudiantId != null ? byId.get(p.etudiantId) : undefined;
        return {
          participationId: p.id,
          etudiantId: p.etudiantId,
          nom: e?.nom ?? '',
          prenom: e?.prenom ?? '',
          numeroInscription: e?.numero_inscription ?? null,
          email: e?.email ?? null, // #227 Added
          ordreImport: p.ordre_import ?? null,
          numEchantillon: p.num_echantillon,
          present: p.est_present,
          note: p.note,
          lotId: p.lotId,
        };
      })
      .sort(compareListing);
  }

  // ---- #227 inline e-mail edit --------------------------------------------

  startEmailEdit(r: RosterRow): void {
    if (r.etudiantId == null) return;
    this.emailError.set(null);
    this.emailDraft.set(r.email ?? '');
    this.emailEditId.set(r.etudiantId);
  }

  cancelEmailEdit(): void {
    this.emailEditId.set(null);
    this.emailDraft.set('');
    this.emailError.set(null);
  }

  /**
   * Patches ONLY the address (PUT accepts a partial body — see #215). An empty
   * value is a deliberate erase, which is why we don't block it: a wrong address
   * is worse than none, so the teacher must be able to take one back out.
   */
  saveEmail(r: RosterRow): void {
    if (r.etudiantId == null) return;
    const value = this.emailDraft().trim();
    if (value && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) {
      this.emailError.set('Adresse e-mail invalide.');
      return;
    }
    const etudiantId = r.etudiantId;
    this.savingEmailId.set(etudiantId);
    this.emailError.set(null);
    this.scoring.updateEtudiant(etudiantId, { email: value }).subscribe({
      next: () => {
        // Patch every row of this student in place — the same person can appear
        // once here, but the directory record is shared, so keep them coherent.
        this.rows.update((list) =>
          list.map((row) => (row.etudiantId === etudiantId ? { ...row, email: value || null } : row)),
        );
        this.savingEmailId.set(null);
        this.cancelEmailEdit();
      },
      error: () => {
        this.savingEmailId.set(null);
        this.emailError.set("Échec de l'enregistrement de l'adresse.");
      },
    });
  }

  // ---- add-new (2-step) ---------------------------------------------------

  openAddNew(): void {
    this.newForm.reset({ prenom: '', nom: '', numeroInscription: '', email: '' });
    this.addError.set(null);
    this.mode.set('new');
  }

  openAddExisting(): void {
    this.addError.set(null);
    this.search.set('');
    this.mode.set('existing');
  }

  cancelAdd(): void {
    this.mode.set('idle');
    this.addError.set(null);
  }

  submitAddNew(): void {
    if (this.newForm.invalid || this.submitting()) return;
    const raw = this.newForm.getRawValue();
    const numero = raw.numeroInscription.trim();

    // Guard the backend's missing uniqueness check: a numéro already in the
    // directory would silently create a second student. Point the user at the
    // existing-student path instead of forking the directory.
    const clash = this.directory().find((e) => normNumero(e.numero_inscription) === normNumero(numero));
    if (clash) {
      this.addError.set(
        `Le numero « ${numero} » est deja utilise (${clash.prenom ?? ''} ${clash.nom ?? ''}). ` +
          `Utilisez « Ajouter un etudiant existant » pour l'inscrire.`,
      );
      return;
    }

    this.submitting.set(true);
    this.addError.set(null);
    const examId = Number(this.id());
    this.scoring
      .createEtudiant({ 
        prenom: raw.prenom.trim(), 
        nom: raw.nom.trim(), 
        numero_inscription: numero, 
        email: raw.email.trim() // #227 Added
      })
      .subscribe({
        next: (student) => {
          // Student now exists in the directory regardless of what happens next.
          this.directory.update((list) => [...list, student]);
          this.scoring
            .createParticipation({ examen_id: examId, etudiantId: student.id })
            .subscribe({
              next: (p) => {
                this.submitting.set(false);
                this.mode.set('idle');
                this.appendRow(p, student);
              },
              error: (err: HttpErrorResponse) => {
                // Student was created but enrolment failed: don't lose that work —
                // keep them in the directory and steer to the existing-picker.
                this.submitting.set(false);
                this.mode.set('existing');
                this.search.set(numero);
                this.addError.set(
                  `Etudiant cree mais inscription echouee (${this.httpMessage(err)}). ` +
                    `Selectionnez-le ci-dessous pour l'inscrire.`,
                );
              },
            });
        },
        error: (err: HttpErrorResponse) => {
          this.submitting.set(false);
          this.addError.set(this.httpMessage(err));
        },
      });
  }

  // ---- enrol existing -----------------------------------------------------

  enrolExisting(e: EtudiantSummary): void {
    if (this.enrollingId() != null) return;
    // Defensive: the picker already excludes enrolled students, but guard the
    // backend's missing duplicate-participation check in case state raced.
    if (this.enrolledIds().has(e.id)) {
      this.addError.set('Cet etudiant est deja inscrit a cet examen.');
      return;
    }
    this.enrollingId.set(e.id);
    this.addError.set(null);
    this.scoring
      .createParticipation({ examen_id: Number(this.id()), etudiantId: e.id })
      .subscribe({
        next: (p) => {
          this.enrollingId.set(null);
          this.appendRow(p, e);
          // Stay open so several students can be enrolled in a row.
        },
        error: (err: HttpErrorResponse) => {
          this.enrollingId.set(null);
          this.addError.set(this.httpMessage(err));
        },
      });
  }

  // ---- bulk import (CSV / Excel) ------------------------------------------

  openImport(): void {
    this.importError.set(null);
    this.importPreview.set(null);
    this.importResult.set(null);
    this.importFileName.set('');
    this.mode.set('import');
  }

  cancelImport(): void {
    this.mode.set('idle');
    this.importPreview.set(null);
    this.importResult.set(null);
    this.importError.set(null);
    this.importFileName.set('');
  }

  /** Parse a selected CSV/.xlsx with SheetJS into a validated preview. */
  onImportFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.importFileName.set(file.name);
    this.importError.set(null);
    this.importResult.set(null);
    this.importPreview.set(null);

    const reader = new FileReader();
    reader.onload = () => {
      try {
        const data = new Uint8Array(reader.result as ArrayBuffer);
        const wb = XLSX.read(data, { type: 'array' });
        const sheet = wb.Sheets[wb.SheetNames[0]];
        // header:1 → array-of-arrays so we control header detection ourselves;
        // raw:false stringifies cells (numéros stay text, not floats).
        const matrix = XLSX.utils.sheet_to_json<unknown[]>(sheet, {
          header: 1,
          raw: false,
          defval: '',
        });
        this.importPreview.set(this.buildPreview(matrix));
      } catch {
        this.importError.set('Fichier illisible. Formats acceptes : CSV, XLSX, XLS.');
      }
      // Let the user re-pick the same file after a correction.
      input.value = '';
    };
    reader.onerror = () => {
      this.importError.set('Echec de lecture du fichier.');
      input.value = '';
    };
    reader.readAsArrayBuffer(file);
  }

  /**
   * Map the raw sheet matrix to validated draft rows. If the first row carries a
   * recognizable header (nom / prénom / a numéro spelling) we map by column name
   * and skip it; otherwise we assume a header-less file in [nom, prénom, numéro]
   * order and treat every row as data. A row is valid only with both a numéro and
   * a nom (the backend rejects the rest with the same rule).
   */
  private buildPreview(matrix: unknown[][]): ImportDraftRow[] {
    const clean = (matrix ?? []).filter((row) =>
      Array.isArray(row) && row.some((c) => (c ?? '').toString().trim() !== ''),
    );
    if (clean.length === 0) return [];

    const header = clean[0].map((h) => normHeader(h));
    let iNom = header.indexOf('nom');
    let iPrenom = header.indexOf('prenom');
    let iNum = header.findIndex((h) => NUM_HEADERS.has(h));
    let iEmail = header.findIndex((h) => EMAIL_HEADERS.has(h)); // #227 Added

    let dataRows: unknown[][];
    if (iNum >= 0 || iNom >= 0) {
      dataRows = clean.slice(1); // recognizable header → drop it
    } else {
      iNom = 0;
      iPrenom = 1;
      iNum = 2; // no header → positional fallback
      iEmail = 3; // #227 Assume 4th column
      dataRows = clean;
    }

    const cell = (row: unknown[], i: number): string =>
      i >= 0 ? (row[i] ?? '').toString().trim() : '';

    return dataRows.map((row, idx) => {
      const nom = cell(row, iNom);
      const prenom = cell(row, iPrenom);
      const numero = cell(row, iNum);
      const email = cell(row, iEmail); // #227 Added
      const valid = !!numero && !!nom;
      let issue: string | null = null;
      if (!valid) {
        if (!numero && !nom) issue = 'Ligne vide / colonnes manquantes';
        else if (!numero) issue = 'Numero manquant';
        else issue = 'Nom manquant';
      }
      return { ligne: idx + 1, nom, prenom, numero_inscription: numero, email, valid, issue };
    });
  }

  /** POST the parsed rows; show the per-row outcome and refresh the roster. */
  submitImport(): void {
    const preview = this.importPreview();
    if (!preview || this.importing()) return;
    const rows: ImportEtudiantRow[] = preview.map((r) => ({
      nom: r.nom,
      prenom: r.prenom,
      numero_inscription: r.numero_inscription,
      email: r.email // #227 Added
    }));
    if (rows.length === 0) {
      this.importError.set('Aucune ligne a importer.');
      return;
    }
    this.importing.set(true);
    this.importError.set(null);
    this.scoring.importEtudiants(Number(this.id()), rows).subscribe({
      next: (result) => {
        this.importing.set(false);
        this.importResult.set(result);
        this.importPreview.set(null);
        // Pull the new enrolments into the roster without leaving the panel.
        this.refreshRoster(Number(this.id()));
      },
      error: (err: HttpErrorResponse) => {
        this.importing.set(false);
        this.importError.set(this.httpMessage(err));
      },
    });
  }

  /** Re-fetch roster data in place (keeps the current mode/panel visible). */
  private refreshRoster(examId: number): void {
    forkJoin({
      participations: this.scoring.listParticipations(examId),
      etudiants: this.scoring.listEtudiants(),
      lots: this.scoring.listLots(examId),
    }).subscribe({
      next: ({ participations, etudiants, lots }) => {
        this.directory.set(etudiants);
        this.lots.set(lots);
        this.rows.set(this.buildRows(participations, etudiants));
        this.store.reloadPrep(); // #185 — tick the workspace stepper
      },
    });
  }

  /** French label for an import row's statut. */
  statutLabel(statut: ImportResult['rows'][number]['statut']): string {
    switch (statut) {
      case 'CREATED':
        return 'Cree + inscrit';
      case 'ENROLLED':
        return 'Inscrit';
      case 'ALREADY_ENROLLED':
        return 'Deja inscrit';
      default:
        return 'Erreur';
    }
  }

  // ---- remove -------------------------------------------------------------

  askRemove(r: RosterRow): void {
    this.removeError.set(null);
    this.confirmRemoveId.set(r.participationId);
  }

  cancelRemove(): void {
    this.confirmRemoveId.set(null);
    this.removeError.set(null);
  }

  confirmRemove(r: RosterRow): void {
    if (this.removingId() === r.participationId) return;
    this.removingId.set(r.participationId);
    this.removeError.set(null);
    this.scoring.deleteParticipation(r.participationId).subscribe({
      next: () => {
        this.removingId.set(null);
        this.confirmRemoveId.set(null);
        this.rows.update((list) => list.filter((x) => x.participationId !== r.participationId));
        this.store.reloadPrep(); // #185 — tick the workspace stepper
      },
      error: (err: HttpErrorResponse) => {
        this.removingId.set(null);
        this.confirmRemoveId.set(null);
        this.removeError.set(`Echec du retrait : ${this.httpMessage(err)}`);
      },
    });
  }

  // ---- helpers ------------------------------------------------------------

  /** Add a freshly-created enrolment to the roster, keeping it name-sorted. */
  private appendRow(p: ParticipationSummary, e: EtudiantSummary): void {
    const row: RosterRow = {
      participationId: p.id,
      etudiantId: p.etudiantId ?? e.id,
      nom: e.nom ?? '',
      prenom: e.prenom ?? '',
      numeroInscription: e.numero_inscription ?? null,
      email: e.email ?? null, // #227 Added
      ordreImport: null, // ajout manuel — passe après le fichier (#256)
      numEchantillon: p.num_echantillon,
      present: p.est_present,
      note: p.note,
      lotId: p.lotId,
    };
    this.rows.update((list) => [...list, row].sort(compareListing));
    this.store.reloadPrep(); // #185 — tick the workspace stepper
  }

  private httpMessage(err: HttpErrorResponse): string {
    if (err.status === 403) return "Vous n'avez pas les droits requis.";
    if ((err.status === 400 || err.status === 409) && typeof err.error?.message === 'string') {
      return err.error.message;
    }
    return 'Reessayez.';
  }

  /** Full name, or a stable fallback for an enrolment whose student row is missing. */
  displayName(r: RosterRow): string {
    const full = `${r.prenom} ${r.nom}`.trim();
    return full || (r.etudiantId != null ? `Etudiant #${r.etudiantId}` : 'Etudiant inconnu');
  }
}