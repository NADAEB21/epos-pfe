import {
  fmtPct,
  libelleDeclencheur,
  libelleLecture,
  libelleOperation,
  phraseProposition,
  sur20,
} from './lecture-deliberation';

/**
 * #363 — gabarits FR des propositions (même doctrine que lecture-indices : pur,
 * déterministe, refus BRUYANT sur un code inconnu). Une phrase par code, les
 * cibles nommées, les deux lectures /brut et /20.
 */
describe('lecture-deliberation — gabarits des propositions (#363)', () => {
  const noms = {
    station: (id: number) => (id === 107 ? 'Station Défauts' : `Station ${id}`),
    critere: (id: number) => (id === 222 ? 'Critère impossible' : `Critère ${id}`),
  };

  it('chaque code de proposition et de lecture a un libellé', () => {
    for (const code of [
      'CRITERE_IMPOSSIBLE', 'CRITERE_SANS_LIEN', 'STATION_EN_ECHEC', 'GRILLE_INCOHERENTE',
      'STATION_NON_SNAPSHOTEE', 'COUVERTURE_INCOMPLETE', 'CIBLE_NON_DELIBERABLE',
      'REPONDERATION_JAMAIS_AUTOMATIQUE',
    ]) {
      expect(libelleLecture(code).length).toBeGreaterThan(3);
    }
    expect(phraseProposition('CRITERE_IMPOSSIBLE')).toContain('renormaliser');
    expect(phraseProposition('STATION_EN_ECHEC')).toContain('exclure');
  });

  it('un code inconnu lève BRUYAMMENT (D7) — jamais « undefined » à l’écran', () => {
    expect(() => libelleLecture('NOUVEAU_CODE_2027')).toThrowError(/inconnu/);
    expect(() => phraseProposition('GRILLE_INCOHERENTE')).toThrowError(/inconnu/);
  });

  it('le déclencheur nomme l’indice, la valeur et le seuil', () => {
    expect(libelleDeclencheur({ code: 'DIFFICULTE', valeur: 0.0556, ic: null, n: 36, seuil: 0.1, regle: 'p <= seuil' }))
      .toContain('p = 0,056');
    const conc = libelleDeclencheur({
      code: 'CONCENTRATION_ECHEC', valeur: 0.7222, ic: null, n: 36, seuil: 0.5, regle: 'x',
      p_value: 0.0005, taux_autres: 0.4167,
    });
    expect(conc).toContain('72 %');
    expect(conc).toContain('42 %');
    expect(conc).toContain('p = 0,0005');
  });

  it('les opérations sont lisibles avec leurs cibles nommées', () => {
    expect(libelleOperation({ type: 'EXCLURE_CRITERE', cibleItemId: 222, cibleStationId: null, nouvelleEchelle: null }, noms))
      .toBe('Retirer le critère « Critère impossible » du barème (renormalisation)');
    expect(libelleOperation({ type: 'EXCLURE_STATION', cibleItemId: null, cibleStationId: 107, nouvelleEchelle: null }, noms))
      .toBe('Exclure la station « Station Défauts » du barème');
    expect(libelleOperation({ type: 'REPONDERER', cibleItemId: null, cibleStationId: 107, nouvelleEchelle: 10 }, noms))
      .toBe('Repondérer la station « Station Défauts » vers /10');
  });

  it('les deux lectures : brut et ≈ /20 ; taux en pourcentage', () => {
    expect(sur20(28.5, 55)).toBe('28,5 / 55 (≈ 10,4 /20)');
    expect(sur20(null, 55)).toBe('—');
    expect(fmtPct(0.5556)).toBe('56 %');
  });
});
