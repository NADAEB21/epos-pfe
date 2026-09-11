import { Injectable, signal } from '@angular/core';

/**
 * #405 — l'état du shell : la barre latérale se replie sous `lg` (tiroir), et
 * la barre du haut porte le titre de la page. Un seul état partagé entre
 * shell, barre latérale et barre du haut.
 */
@Injectable({ providedIn: 'root' })
export class LayoutStore {
  /** Tiroir ouvert (écrans étroits uniquement — sur `lg` la barre est toujours visible). */
  readonly sidebarOpen = signal(false);

  toggleSidebar(): void {
    this.sidebarOpen.update((v) => !v);
  }

  closeSidebar(): void {
    this.sidebarOpen.set(false);
  }
}
