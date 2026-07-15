import { resolveLaneState, Slot } from './suivi.component';
import { RotationStatus } from '../../core/api/models';

/**
 * #182 — regression pin.
 *
 * A station with NO rotations must never resolve to 'done'. The board used to fall
 * through live → upcoming → "Terminée", so a station whose rotations were never
 * generated displayed as already finished. During a real exam simulation the
 * responsable read "Terminée" across every station and concluded it was too late —
 * when in fact nothing had run, no data was lost, and rotations could still be
 * generated (RotationGenerationService permits it while the exam is EN_COURS).
 *
 * The empty case must resolve to 'sansRotations' — an unknown, recoverable state.
 */
describe('resolveLaneState (#182)', () => {
  const DUREE = 15 * 60_000; // 15 min
  const T = (h: number, m = 0) => new Date(2026, 6, 13, h, m).getTime();

  const slot = (
    debutMs: number,
    ordre = 1,
    statut: RotationStatus = 'EN_ATTENTE',
  ): Slot => ({
    rotationId: ordre,
    ordrePassage: ordre,
    debutMs,
    debutLabel: '',
    statut,
  });

  describe('the invariant: empty is never done', () => {
    it('resolves an empty slot set to sansRotations, NOT done', () => {
      expect(resolveLaneState([], T(18), DUREE)).toBe('sansRotations');
    });

    it('stays sansRotations however late the clock is', () => {
      // The old bug: any time past the (non-existent) plan fell through to "Terminée".
      expect(resolveLaneState([], T(23, 59), DUREE)).toBe('sansRotations');
    });

    it('stays sansRotations even before the exam clock resolves', () => {
      expect(resolveLaneState([], null, DUREE)).toBe('sansRotations');
    });
  });

  describe('a real plan still resolves normally (no regression)', () => {
    const plan = [slot(T(9), 1), slot(T(9, 15), 2), slot(T(9, 30), 3)];

    it('is upcoming before the first slot', () => {
      expect(resolveLaneState(plan, T(8, 30), DUREE)).toBe('upcoming');
    });

    it('is live inside a slot', () => {
      expect(resolveLaneState(plan, T(9, 20), DUREE)).toBe('live');
    });

    it('is live on the exact slot boundary (inclusive start)', () => {
      expect(resolveLaneState(plan, T(9, 15), DUREE)).toBe('live');
    });

    it('falls back to upcoming when the clock has not resolved', () => {
      expect(resolveLaneState(plan, null, DUREE)).toBe('upcoming');
    });
  });

  /**
   * #184 — the créneau is indicative, never a completion verdict.
   *
   * A station whose clock has elapsed but whose passages were never validated is
   * NOT finished — the évaluateur may still be grading (time never blocks writes).
   * It must resolve to 'enRetard' (dépassement), not 'done'. Only when every slot
   * is really TERMINE (évaluateur's validerLot) does the station read 'done'.
   */
  describe('elapsed is not done unless validated (#184)', () => {
    const T2 = (h: number, m = 0) => new Date(2026, 6, 13, h, m).getTime();
    const done = (debutMs: number, ordre = 1) => slot(debutMs, ordre, 'TERMINE');

    it('is enRetard once the plan elapsed but nothing is validated', () => {
      const plan = [slot(T2(9), 1), slot(T2(9, 15), 2), slot(T2(9, 30), 3)];
      expect(resolveLaneState(plan, T2(18), DUREE)).toBe('enRetard');
    });

    it('is enRetard in the gap after the last slot ends (was the phantom "done")', () => {
      const plan = [slot(T2(9), 1), slot(T2(9, 15), 2), slot(T2(9, 30), 3)];
      expect(resolveLaneState(plan, T2(9, 46), DUREE)).toBe('enRetard');
    });

    it('is done only when every elapsed slot is really TERMINE', () => {
      const plan = [done(T2(9), 1), done(T2(9, 15), 2), done(T2(9, 30), 3)];
      expect(resolveLaneState(plan, T2(18), DUREE)).toBe('done');
    });

    it('is enRetard when validation is only partial', () => {
      const plan = [done(T2(9), 1), slot(T2(9, 15), 2), done(T2(9, 30), 3)];
      expect(resolveLaneState(plan, T2(18), DUREE)).toBe('enRetard');
    });

    it('still resolves live/upcoming before consulting completion', () => {
      // A slot inside its window is live regardless of statut — the terminal
      // completion branch is only reached once the whole plan has elapsed.
      const plan = [slot(T2(9), 1, 'TERMINE'), slot(T2(9, 15), 2)];
      expect(resolveLaneState(plan, T2(9, 20), DUREE)).toBe('live');
      expect(resolveLaneState(plan, T2(8, 30), DUREE)).toBe('upcoming');
    });
  });
});
