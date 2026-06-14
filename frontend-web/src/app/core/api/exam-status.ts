import { StatutExamen } from './models';

/** Canonical lifecycle order. */
export const LIFECYCLE: StatutExamen[] = [
  'BROUILLON',
  'CONFIGURE',
  'EN_COURS',
  'TERMINE',
  'ARCHIVE',
];

/** Plain stage labels (lifecycle steps + persisted status). */
export const STATUT_LABELS: Record<StatutExamen, string> = {
  BROUILLON: 'Brouillon',
  CONFIGURE: 'Configuré',
  EN_COURS: 'En cours',
  TERMINE: 'Terminé',
  ARCHIVE: 'Archivé',
};

/** Today as `yyyy-MM-dd`, local — same shape as `Examen.dateExamen`. */
export function todayStr(now: Date = new Date()): string {
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(
    now.getDate(),
  ).padStart(2, '0')}`;
}

/** The exam's date is strictly in the future (`yyyy-MM-dd` sorts chronologically). */
export function isFutureDate(dateExamen: string | null | undefined, now: Date = new Date()): boolean {
  return !!dateExamen && dateExamen > todayStr(now);
}

/** The exam's date is today — the only day it may be launched (jour J). */
export function isExamDay(dateExamen: string | null | undefined, now: Date = new Date()): boolean {
  return !!dateExamen && dateExamen === todayStr(now);
}

/**
 * "À venir" (upcoming) is a DERIVED display state, not a persisted status: a
 * CONFIGURE exam whose date is still in the future is locked and ready but cannot
 * be launched until the jour J. The lifecycle itself stays
 * BROUILLON→CONFIGURE→EN_COURS→TERMINE→ARCHIVE — a future exam must never read
 * "En cours" (the Lancement date gate forbids launching before the date).
 */
export function isAVenir(statut: StatutExamen, dateExamen: string | null | undefined, now: Date = new Date()): boolean {
  return statut === 'CONFIGURE' && isFutureDate(dateExamen, now);
}

/** Display label, mapping CONFIGURE + future date to "À venir". */
export function statutDisplayLabel(
  statut: StatutExamen,
  dateExamen: string | null | undefined,
  now: Date = new Date(),
): string {
  return isAVenir(statut, dateExamen, now) ? 'À venir' : STATUT_LABELS[statut];
}
