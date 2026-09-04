import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AppShellComponent } from './app-shell.component';
import { LayoutStore } from './layout.store';
import { AuthStore } from '../auth/auth.store';

/**
 * #405 — le shell : une barre latérale permanente (lg) et un tiroir sous lg piloté
 * par LayoutStore ; le titre de page de la barre du haut vient de la route.
 */
describe('AppShellComponent — shell responsive (#405)', () => {
  function build() {
    TestBed.configureTestingModule({
      imports: [AppShellComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(AuthStore).setUser({
      email: 'resp@epos.tn', userId: 2, accessTokenExpiresAt: new Date(Date.now() + 3600_000),
      authorities: [{ role: 'RESPONSABLE_MATIERE', matiereId: 1 }],
    });
    const fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, layout: TestBed.inject(LayoutStore) };
  }

  it('rend une seule barre latérale tant que le tiroir est fermé', () => {
    const { el } = build();
    expect(el.querySelectorAll('app-sidebar').length).toBe(1);
  });

  it('ouvrir le tiroir ajoute la barre en surimpression ; un clic sur le voile la ferme', () => {
    const { fixture, el, layout } = build();
    layout.toggleSidebar();
    fixture.detectChanges();
    expect(el.querySelectorAll('app-sidebar').length).toBe(2);
    (el.querySelector('.fixed .absolute.inset-0') as HTMLElement).click();
    fixture.detectChanges();
    expect(layout.sidebarOpen()).toBeFalse();
    expect(el.querySelectorAll('app-sidebar').length).toBe(1);
  });

  it('la navigation porte des icônes et des libellés accentués', () => {
    const { el } = build();
    const labels = Array.from(el.querySelectorAll('app-sidebar a')).map((a) => a.textContent?.trim());
    expect(labels).toContain('Bibliothèque');
    expect(labels).toContain('Évaluateurs');
    expect(el.querySelectorAll('app-sidebar app-icon svg path').length).toBeGreaterThan(5);
  });
});
