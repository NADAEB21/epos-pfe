import { TestBed } from '@angular/core/testing';
import { LectureIndiceComponent } from './lecture-indice.component';
import { Indice } from './lecture-indices';

/**
 * #360 (F4) — LectureIndice, specs DOM. Épingle les trois états du contrat :
 * lecture nominale (style neutre), refus du moteur (style refus + raison
 * VERBATIM), et code INCONNU → état dégradé VISIBLE au lieu d'un crash de
 * l'écran hôte (ADR-0015 lecture dégradée ; lecture-indices.ts lève exprès,
 * le composant attrape). Le 3ᵉ spec est la paire discriminante : sur la
 * version sans garde, createComponent + detectChanges LÈVE.
 */
describe('LectureIndiceComponent — #360 (F4)', () => {
  const indice = (patch: Partial<Indice>): Indice => ({
    code: 'DISCRIMINATION',
    statut: 'CONCLUANT',
    n: 20,
    valeur: 0.42,
    ic: null,
    raison: null,
    details: {},
    ...patch,
  });

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [LectureIndiceComponent] });
  });

  it('indice absent → ne rend rien', () => {
    const fixture = TestBed.createComponent(LectureIndiceComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('div')).toBeNull();
  });

  it('lecture nominale → libellé + phrase FR, style neutre (pas ambre)', () => {
    const fixture = TestBed.createComponent(LectureIndiceComponent);
    fixture.componentRef.setInput('indice', indice({ valeur: 0.42 }));
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('Discrimination');
    expect(el.textContent).toContain('discrimine bien');
    expect(el.textContent).toContain('n=20');
    expect(el.querySelector('div')!.className).not.toContain('amber');
  });

  it('refus du moteur → raison VERBATIM, style refus', () => {
    const fixture = TestBed.createComponent(LectureIndiceComponent);
    fixture.componentRef.setInput(
      'indice',
      indice({
        statut: 'NON_CONCLUANT',
        valeur: null,
        raison: 'non concluant — effectif insuffisant (n=9 < 15)',
      }),
    );
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('non concluant — effectif insuffisant (n=9 < 15)');
    expect(el.querySelector('div')!.className).toContain('amber');
  });

  it("code inconnu (ai-service en avance) → dégradé VISIBLE, l'écran hôte ne tombe pas", () => {
    const fixture = TestBed.createComponent(LectureIndiceComponent);
    fixture.componentRef.setInput(
      'indice',
      indice({ code: 'NOUVEL_INDICE_2027' as Indice['code'], valeur: 0.5 }),
    );

    expect(() => fixture.detectChanges()).not.toThrow();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('NOUVEL_INDICE_2027');
    expect(el.textContent).toContain('lecture indisponible');
    expect(el.querySelector('div')!.className).toContain('amber');
  });
});
