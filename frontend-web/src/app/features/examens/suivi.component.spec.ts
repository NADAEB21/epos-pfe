import { resolveLaneState, Slot } from './suivi.component';
import { RotationStatus } from '../../core/api/models';

/**
 * resolveLaneState — réécrit pour #208, pas restauré.
 *
 * <p>L'ancienne version de ce spec épinglait le modèle à l'horloge : elle passait un
 * « effectiveNowMs » et une durée, et vérifiait qu'un créneau écoulé donnait « enRetard »
 * (#184). Ce modèle est retiré : l'état d'une station se LIT désormais dans la progression
 * servie par le backend (statut stocké #207), et « enRetard »/« dépassement » n'existe plus
 * — ce n'était pas un état mais une opinion de l'horloge sur un travail qu'elle ne voyait
 * pas (constaté le 2026-07-21 : badges « dépassement » sur des rotations TERMINE en base).
 *
 * <p>Ce qui SURVIT de l'ancien spec — parce que c'est un invariant, pas de l'horloge :
 * <b>#182, une station sans rotations ne se lit JAMAIS « done »</b>. Le tableau tombait
 * autrefois en cascade jusqu'à « Terminée », et une simulation d'examen entière a été
 * perdue parce que le responsable a lu « Terminée » partout et conclu qu'il était trop
 * tard. La branche vide se résout AVANT tout le reste, et vers un état récupérable.
 */
describe('resolveLaneState (#182 / #208)', () => {
  const slot = (ordre = 1, statut: RotationStatus = 'EN_ATTENTE'): Slot => ({
    rotationId: ordre,
    ordrePassage: ordre,
    debutMs: 0,
    debutLabel: '',
    statut,
  });

  describe("l'invariant #182 : vide n'est jamais fini", () => {
    it('un planning vide se résout en sansRotations, PAS en done', () => {
      expect(resolveLaneState([], null)).toBe('sansRotations');
    });

    it('vide reste sansRotations même si le backend annonce un statut', () => {
      // Défense en profondeur : même une progression incohérente (statut sans passages)
      // ne doit pas faire lire « Terminée » sur une station sans planning.
      expect(resolveLaneState([], 'TERMINE')).toBe('sansRotations');
      expect(resolveLaneState([], 'EN_COURS')).toBe('sansRotations');
    });
  });

  describe("l'état est LU depuis le statut servi (#208)", () => {
    const plan = [slot(1), slot(2)];

    it('EN_COURS → live', () => {
      expect(resolveLaneState(plan, 'EN_COURS')).toBe('live');
    });

    it('TERMINE → done', () => {
      expect(resolveLaneState(plan, 'TERMINE')).toBe('done');
    });

    it("EN_ATTENTE → upcoming — la vague n'a pas commencé", () => {
      expect(resolveLaneState(plan, 'EN_ATTENTE')).toBe('upcoming');
    });

    it('statut absent (station hors de la vague affichée) → upcoming, jamais done', () => {
      // Une station que la progression ne couvre pas est INCONNUE : la lire « Terminée »
      // recréerait le fantôme #182 par un autre chemin.
      expect(resolveLaneState(plan, null)).toBe('upcoming');
    });
  });
});
