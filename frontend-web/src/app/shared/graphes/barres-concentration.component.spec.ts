import { TestBed } from '@angular/core/testing';
import { provideEchartsCore } from 'ngx-echarts';
import { loadEcharts } from './echarts-setup';
import { BarresConcentrationComponent } from './barres-concentration.component';

/**
 * #356 — BarresConcentration. Épingle : la ligne « valeurPct null » reste
 * visible sans barre remplie, le repli par défaut vs le texte de refus
 * VERBATIM (ADR-0021 D2), la garde « jamais de % sans son n » côté options
 * de graphe, et l'émission de `ligneClick`.
 */
describe('BarresConcentrationComponent — #356 (spec N4)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [BarresConcentrationComponent],
      providers: [provideEchartsCore({ echarts: loadEcharts })],
    });
  });

  it('lignes absent (entrée malformée) → rien du tout', () => {
    const fixture = TestBed.createComponent(BarresConcentrationComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[echarts]')).toBeNull();
  });

  it('lignes non-tableau → rien du tout', () => {
    const fixture = TestBed.createComponent(BarresConcentrationComponent);
    fixture.componentRef.setInput('lignes', 'oops');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[echarts]')).toBeNull();
  });

  it('valeurPct null SANS detail → repli sur le texte par défaut', () => {
    const fixture = TestBed.createComponent(BarresConcentrationComponent);
    fixture.componentRef.setInput('lignes', [{ id: 1, label: 'Station A', valeurPct: null, n: 0 }]);
    fixture.detectChanges();

    const opts = fixture.componentInstance.chartOptions() as any;
    expect(opts.series[0].label.formatter({ dataIndex: 0 })).toBe('aucune notation verrouillée');
    // Aucune barre remplie pour cette ligne : couleur transparente.
    expect(opts.series[0].data[0].itemStyle.color).toBe('transparent');
  });

  it('valeurPct null AVEC detail → le texte de refus est rendu VERBATIM', () => {
    const fixture = TestBed.createComponent(BarresConcentrationComponent);
    fixture.componentRef.setInput('lignes', [
      { id: 2, label: 'Station B', valeurPct: null, n: 0, detail: 'comparaison non concluante — effectif insuffisant' },
    ]);
    fixture.detectChanges();

    const opts = fixture.componentInstance.chartOptions() as any;
    expect(opts.series[0].label.formatter({ dataIndex: 0 })).toBe(
      'comparaison non concluante — effectif insuffisant',
    );
  });

  it('une valeur est TOUJOURS accompagnée de son n', () => {
    const fixture = TestBed.createComponent(BarresConcentrationComponent);
    fixture.componentRef.setInput('lignes', [{ id: 3, label: 'Station C', valeurPct: 50, n: 2 }]);
    fixture.detectChanges();

    const opts = fixture.componentInstance.chartOptions() as any;
    const label = opts.series[0].label.formatter({ dataIndex: 0 }) as string;
    expect(label).toContain('50');
    expect(label).toContain('sur 2');
  });

  it("émet ligneClick avec l'id de la ligne cliquée (ordre ECharts inversé pris en compte)", () => {
    const fixture = TestBed.createComponent(BarresConcentrationComponent);
    const component = fixture.componentInstance;
    fixture.componentRef.setInput('lignes', [
      { id: 10, label: 'Première', valeurPct: 80, n: 5 },
      { id: 20, label: 'Deuxième', valeurPct: 20, n: 5 },
    ]);
    fixture.detectChanges();

    let emitted: number | null = null;
    component.ligneClick.subscribe((id) => (emitted = id));
    // ECharts affiche les catégories de bas en haut ; le composant inverse
    // en interne pour respecter l'ordre d'entrée — dataIndex=1 (le DERNIER
    // du tableau ECharts inversé) correspond à la PREMIÈRE ligne d'entrée.
    component.onClick({ dataIndex: 1 });

    expect<number | null>(emitted).toBe(10);
  });

  it('sans dataIndex exploitable, ne fait rien', () => {
    const fixture = TestBed.createComponent(BarresConcentrationComponent);
    const component = fixture.componentInstance;
    fixture.componentRef.setInput('lignes', [{ id: 1, label: 'A', valeurPct: 50, n: 1 }]);
    fixture.detectChanges();

    let called = false;
    component.ligneClick.subscribe(() => (called = true));
    component.onClick({});

    expect(called).toBeFalse();
  });
});
