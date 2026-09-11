import { TestBed } from '@angular/core/testing';
import { provideEchartsCore } from 'ngx-echarts';
import { loadEcharts } from './echarts-setup';
import { HistogrammeStationComponent } from './histogramme-station.component';

/**
 * #356 — HistogrammeStation. Épingle le contrat de la spec N4 : les deux
 * états obligatoires (n=0 vs entrée malformée, volontairement DISTINCTS),
 * le rendu nominal (seuilLabel + n toujours visibles), et l'émission de
 * `binClick`. Pas de test pixel-à-pixel du canvas ECharts (hors de portée
 * de Karma) — le contrat vérifié est celui des ENTRÉES/ÉVÉNEMENTS/ÉTATS,
 * ce que la spec N4 définit réellement.
 */
describe('HistogrammeStationComponent — #356 (spec N4)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HistogrammeStationComponent],
      // La même fabrique lazy que la prod (spec N4 règle 5) — les modules bar
      // sont enregistrés par elle, comme au premier graphe réel.
      providers: [provideEchartsCore({ echarts: loadEcharts })],
    });
  });

  it('n === 0 → état vide, jamais un graphe vide ni des zéros silencieux', () => {
    const fixture = TestBed.createComponent(HistogrammeStationComponent);
    fixture.componentRef.setInput('bins', []);
    fixture.componentRef.setInput('n', 0);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Aucune notation verrouillée — pas de distribution à lire.');
    expect(fixture.nativeElement.querySelector('[echarts]')).toBeNull();
  });

  it('bins absent (entrée malformée) → rien du tout, même avec n > 0', () => {
    const fixture = TestBed.createComponent(HistogrammeStationComponent);
    fixture.componentRef.setInput('n', 5); // bins jamais renseigné
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe('');
    expect(fixture.nativeElement.querySelector('[echarts]')).toBeNull();
  });

  it('bins non-tableau (null) → rien du tout', () => {
    const fixture = TestBed.createComponent(HistogrammeStationComponent);
    fixture.componentRef.setInput('bins', null);
    fixture.componentRef.setInput('n', 5);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe('');
  });

  it('nominal → rend le graphe, avec seuilLabel et n toujours visibles', () => {
    const fixture = TestBed.createComponent(HistogrammeStationComponent);
    fixture.componentRef.setInput('bins', [
      { label: '0–4', count: 2, pct: 40, sousSeuil: true },
      { label: '4–8', count: 3, pct: 60, sousSeuil: false },
    ]);
    fixture.componentRef.setInput('n', 5);
    fixture.componentRef.setInput('seuilLabel', 'échec = note < 50 % du barème');
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('n = 5');
    expect(text).toContain('échec = note < 50');
    expect(fixture.nativeElement.querySelector('[echarts]')).not.toBeNull();
  });

  it('émet binClick avec le label du bac cliqué', () => {
    const fixture = TestBed.createComponent(HistogrammeStationComponent);
    const component = fixture.componentInstance;
    fixture.componentRef.setInput('bins', [{ label: '0–4', count: 1, pct: 100, sousSeuil: true }]);
    fixture.componentRef.setInput('n', 1);
    fixture.detectChanges();

    let emitted: string | null = null;
    component.binClick.subscribe((label) => (emitted = label));
    component.onClick({ name: '0–4' });

    expect<string | null>(emitted).toBe('0–4');
  });

  it("n'émet rien si l'événement ECharts ne porte pas de nom exploitable", () => {
    const fixture = TestBed.createComponent(HistogrammeStationComponent);
    const component = fixture.componentInstance;
    fixture.componentRef.setInput('bins', [{ label: '0–4', count: 1, pct: 100, sousSeuil: true }]);
    fixture.componentRef.setInput('n', 1);
    fixture.detectChanges();

    let called = false;
    component.binClick.subscribe(() => (called = true));
    component.onClick({});

    expect(called).toBeFalse();
  });
});
