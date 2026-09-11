import { TestBed } from '@angular/core/testing';
import { IconComponent } from './icon.component';

/** #405 — le jeu d'icônes inline : un nom connu trace, un nom inconnu ne trace rien (jamais une icône fausse). */
describe('IconComponent (#405)', () => {
  function build(name: string, label = '') {
    TestBed.configureTestingModule({ imports: [IconComponent] });
    const fixture = TestBed.createComponent(IconComponent);
    fixture.componentRef.setInput('name', name);
    fixture.componentRef.setInput('label', label);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('trace un chemin pour un nom connu, décoratif par défaut', () => {
    const el = build('home');
    expect(el.querySelectorAll('path').length).toBeGreaterThan(0);
    expect(el.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('ne trace rien pour un nom inconnu', () => {
    expect(build('nope-2027').querySelectorAll('path').length).toBe(0);
  });

  it('un libellé rend l’icône signifiante (role img)', () => {
    const svg = build('warning', 'Attention')!.querySelector('svg')!;
    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.getAttribute('aria-label')).toBe('Attention');
  });
});
