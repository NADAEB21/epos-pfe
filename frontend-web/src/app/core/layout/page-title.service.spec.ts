import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { Router, provideRouter } from '@angular/router';
import { PageTitleService } from './page-title.service';

@Component({ standalone: true, template: '' })
class Vide {}

/** #405 — le titre vient de `data.title` de la route la plus profonde ; l'onglet du navigateur suit. */
describe('PageTitleService (#405)', () => {
  it('lit data.title, hérite du parent quand l’enfant n’en a pas, et nomme l’onglet', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'examens/:id', data: { title: 'Examen' }, component: Vide, children: [{ path: 'resultats', component: Vide }] },
          { path: 'accueil', data: { title: 'Accueil' }, component: Vide },
        ]),
      ],
    });
    const svc = TestBed.inject(PageTitleService);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/examens/92/resultats');
    expect(svc.current()).toBe('Examen');
    expect(TestBed.inject(Title).getTitle()).toBe('Examen · EPOS');
    await router.navigateByUrl('/accueil');
    expect(svc.current()).toBe('Accueil');
  });
});
