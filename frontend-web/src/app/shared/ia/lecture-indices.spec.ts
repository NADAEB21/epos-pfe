import { Indice, libelleCode, lireIndice } from './lecture-indices';

/** Un Indice minimal, complété par le test. Miroir de conftest.py côté ai-service. */
function indice(partial: Partial<Indice>): Indice {
  return {
    code: 'DIFFICULTE',
    statut: 'CONCLUANT',
    n: 20,
    valeur: null,
    ic: null,
    raison: null,
    details: {},
    ...partial,
  };
}

describe('lireIndice — #360 gabarits FR déterministes', () => {
  // ---- refus : JAMAIS reformulé --------------------------------------------

  it('un refus NON_CONCLUANT rend le raison du backend TEL QUEL, sans reformulation', () => {
    const i = indice({
      statut: 'NON_CONCLUANT',
      raison: 'non concluant — effectif insuffisant (n=9 < 10)',
    });
    expect(lireIndice(i)).toBe('non concluant — effectif insuffisant (n=9 < 10)');
  });

  it('un refus « comparaison » (sévérité/discrimination) est aussi rendu verbatim', () => {
    const i = indice({
      statut: 'NON_CONCLUANT',
      raison: 'comparaison non concluante — un seul évaluateur a noté cette station',
    });
    expect(lireIndice(i)).toBe('comparaison non concluante — un seul évaluateur a noté cette station');
  });

  it('un refus sans raison rend un texte de repli neutre, jamais une phrase inventée', () => {
    const i = indice({ statut: 'NON_CONCLUANT', raison: null });
    expect(lireIndice(i)).toBe('non concluant');
  });

  it('un indice CONCLUANT sans valeur (état incohérent) ne fabrique rien', () => {
    const i = indice({ statut: 'CONCLUANT', valeur: null });
    expect(lireIndice(i)).toBe('');
  });

  // ---- difficulté -----------------------------------------------------------

  it('difficulté 58 % (exemple F3) → bande "équilibrée"', () => {
    const i = indice({ code: 'DIFFICULTE', valeur: 0.58 });
    const texte = lireIndice(i);
    expect(texte).toContain('58 %');
    expect(texte).toContain('difficulté équilibrée');
  });

  it('difficulté 5 % → "très difficile"', () => {
    const i = indice({ code: 'DIFFICULTE', valeur: 0.05 });
    expect(lireIndice(i)).toContain('très difficile');
  });

  it('difficulté 95 % → "très facile" et signale le manque d’information', () => {
    const i = indice({ code: 'DIFFICULTE', valeur: 0.95 });
    const texte = lireIndice(i);
    expect(texte).toContain('très facile');
    expect(texte).toContain("n'apporte guère d'information");
  });

  it('les bornes des bandes sont inclusives côté haut (0,2 exact → "difficile", pas "très difficile")', () => {
    const i = indice({ code: 'DIFFICULTE', valeur: 0.2 });
    const texte = lireIndice(i);
    expect(texte).not.toContain('très difficile');
    expect(texte).toContain('difficile');
  });

  // ---- discrimination ---------------------------------------------------------

  it('discrimination parfaite (r=1) → "discrimine bien"', () => {
    const i = indice({ code: 'DISCRIMINATION', valeur: 1.0 });
    expect(lireIndice(i)).toContain('discrimine bien');
  });

  it('discrimination quasi nulle (r=0.05) → citation exacte du plan IA/BI', () => {
    const i = indice({ code: 'DISCRIMINATION', valeur: 0.05 });
    expect(lireIndice(i)).toContain("n'a séparé personne");
  });

  it('discrimination négative (r=-1) → alerte "à l’envers", pas une simple bande basse', () => {
    const i = indice({ code: 'DISCRIMINATION', valeur: -1.0 });
    expect(lireIndice(i)).toContain("à l'envers");
  });

  it('affiche l’intervalle de confiance quand il est présent', () => {
    const i = indice({ code: 'DISCRIMINATION', valeur: 0.42, ic: [0.18, 0.61] });
    const texte = lireIndice(i);
    expect(texte).toContain('intervalle de confiance 95 %');
    expect(texte).toContain('0,18');
    expect(texte).toContain('0,61');
  });

  it('n’affiche rien sur l’IC quand il est absent', () => {
    const i = indice({ code: 'DISCRIMINATION', valeur: 0.42, ic: null });
    expect(lireIndice(i)).not.toContain('intervalle de confiance');
  });

  // ---- alpha de Cronbach ------------------------------------------------------

  it('alpha=1.0 sur 3 critères → "excellente" et nomme k', () => {
    const i = indice({ code: 'ALPHA_CRONBACH', valeur: 1.0, details: { k: 3 } });
    const texte = lireIndice(i);
    expect(texte).toContain('excellente');
    expect(texte).toContain('3 critère(s)');
  });

  it('alpha négatif (-3.0, exemple test_engine_alpha) → "incohérente", jamais présenté comme un score', () => {
    const i = indice({ code: 'ALPHA_CRONBACH', valeur: -3.0, details: { k: 3 } });
    expect(lireIndice(i)).toContain('incohérente');
  });

  // ---- concentration d'échec -----------------------------------------------

  it('taux significativement plus élevé que les autres stations → le dit', () => {
    const i = indice({
      code: 'CONCENTRATION_ECHEC',
      valeur: 0.75,
      details: { taux_autres: 0.1, p_value: 0.001 },
    });
    const texte = lireIndice(i);
    expect(texte).toContain('75 %');
    expect(texte).toContain('plus élevé');
  });

  it('écart non significatif (p_value haute) → ne conclut PAS à une différence', () => {
    const i = indice({
      code: 'CONCENTRATION_ECHEC',
      valeur: 0.5,
      details: { taux_autres: 0.45, p_value: 0.8 },
    });
    expect(lireIndice(i)).toContain('ne se distingue pas statistiquement');
  });

  // ---- sévérité évaluateur ----------------------------------------------------

  it('écart +2 (défaut planté F1, cf. test_engine_severite.py) → "plus sévèrement" avec les deux moyennes', () => {
    const i = indice({
      code: 'SEVERITE_EVALUATEUR',
      valeur: 2.0,
      details: { moyenne_evaluateur: 12.0, moyenne_autres: 10.0 },
    });
    const texte = lireIndice(i);
    expect(texte).toContain('plus sévèrement');
    expect(texte).toContain('12,0');
    expect(texte).toContain('10,0');
  });

  it('écart négatif → "plus indulgemment", jamais un jugement de valeur', () => {
    const i = indice({
      code: 'SEVERITE_EVALUATEUR',
      valeur: -2.5,
      details: { moyenne_evaluateur: 9.0, moyenne_autres: 11.5 },
    });
    expect(lireIndice(i)).toContain('plus indulgemment');
  });

  // ---- libellés ---------------------------------------------------------------

  it('libelleCode couvre les cinq indices sans lever', () => {
    const codes: Indice['code'][] = [
      'DIFFICULTE',
      'DISCRIMINATION',
      'ALPHA_CRONBACH',
      'CONCENTRATION_ECHEC',
      'SEVERITE_EVALUATEUR',
    ];
    for (const c of codes) {
      expect(libelleCode(c)).toBeTruthy();
    }
  });

  // ---- aucun texte technique brut -------------------------------------------

  it('aucune sortie ne contient de jargon statistique brut (p=, r=, point-biserial)', () => {
    const cas: Indice[] = [
      indice({ code: 'DIFFICULTE', valeur: 0.6 }),
      indice({ code: 'DISCRIMINATION', valeur: 0.3 }),
      indice({ code: 'ALPHA_CRONBACH', valeur: 0.8, details: { k: 4 } }),
      indice({ code: 'CONCENTRATION_ECHEC', valeur: 0.4, details: { taux_autres: 0.2, p_value: 0.01 } }),
      indice({
        code: 'SEVERITE_EVALUATEUR',
        valeur: 1.0,
        details: { moyenne_evaluateur: 11, moyenne_autres: 10 },
      }),
    ];
    for (const i of cas) {
      const texte = lireIndice(i);
      expect(texte).not.toMatch(/point-biserial/i);
      expect(texte).not.toMatch(/\bp\s*=\s*[\d.]/);
      expect(texte).not.toMatch(/\br\s*=\s*-?[\d.]/);
    }
  });
});
