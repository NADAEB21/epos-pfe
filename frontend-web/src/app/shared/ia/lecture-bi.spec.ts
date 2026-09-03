import { fmtDate, fmtNum, fmtTaux, lectureBiSure, libelleLectureBi, sur20 } from './lecture-bi';

/**
 * #365 (N10) — gabarits BI : l'union fermée des codes, le refus BRUYANT sur un
 * code inconnu (et sa version « sûre » qui dégrade sans casser), et les
 * formats fr-FR que les deux écrans BI affichent.
 */
describe('lecture-bi (#365)', () => {
  it('traduit les quatre codes fermés', () => {
    expect(libelleLectureBi('AUCUN_EXAMEN_CLOS')).toBe('Aucune session close');
    expect(libelleLectureBi('SANS_NOTATION')).toBe('Session sans notation');
    expect(libelleLectureBi('COUVERTURE_INCOMPLETE')).toContain('incomplète');
    expect(libelleLectureBi('EFFECTIF_INSUFFISANT')).toBe('Effectif insuffisant');
  });

  it('lève sur un code inconnu — jamais une phrase inventée', () => {
    expect(() => libelleLectureBi('NOUVEAU_CODE_2027')).toThrowError(/inconnu/);
  });

  it('lectureBiSure dégrade visiblement au lieu de casser', () => {
    expect(lectureBiSure('SANS_NOTATION')).toEqual({ libelle: 'Session sans notation', degrade: false });
    const d = lectureBiSure('NOUVEAU_CODE_2027');
    expect(d.degrade).toBeTrue();
    expect(d.libelle).toContain('NOUVEAU_CODE_2027');
    expect(d.libelle).toContain('indisponible');
  });

  it('sur20 ramène un total brut à /20, null sans dénominateur', () => {
    expect(sur20(28.5, 55)).toBeCloseTo(10.36, 2);
    expect(sur20(10, 0)).toBeNull();
    expect(sur20(null, 20)).toBeNull();
  });

  it('formats fr-FR : nombre, taux, date', () => {
    expect(fmtNum(10.3636)).toBe('10,4');
    expect(fmtNum(null)).toBe('—');
    expect(fmtTaux(0.5556)).toBe('56 %');
    expect(fmtTaux(null)).toBe('—');
    expect(fmtDate('2026-06-10')).toBe('10/06/2026');
    expect(fmtDate(null)).toBe('date inconnue');
  });
});
