import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { ActivatedRouteSnapshot, NavigationEnd, Router } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map, startWith } from 'rxjs';

const APP = 'EPOS';

/**
 * #405 — le titre de la page courante, lu dans `data.title` de la route la plus
 * profonde (les enfants héritent du parent : l'onglet d'un examen garde
 * « Examen »). Alimente la barre du haut ET l'onglet du navigateur.
 */
@Injectable({ providedIn: 'root' })
export class PageTitleService {
  private readonly router = inject(Router);
  private readonly title = inject(Title);

  readonly current = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      startWith(null),
      map(() => this.resolve()),
    ),
    { initialValue: '' },
  );

  private resolve(): string {
    let r: ActivatedRouteSnapshot | null = this.router.routerState.snapshot.root;
    let title = '';
    while (r) {
      if (typeof r.data?.['title'] === 'string') title = r.data['title'];
      r = r.firstChild;
    }
    this.title.setTitle(title ? `${title} · ${APP}` : `${APP} — Plateforme d'évaluation`);
    return title;
  }
}
