import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SidebarComponent } from './sidebar.component';
import { TopbarComponent } from './topbar.component';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, SidebarComponent, TopbarComponent],
  template: `
    <div class="min-h-screen flex">
      <app-sidebar class="shrink-0" />
      <div class="flex-1 flex flex-col min-w-0">
        <app-topbar />
        <main class="flex-1 px-8 py-6 overflow-auto">
          <router-outlet />
        </main>
      </div>
    </div>
  `,
})
export class AppShellComponent {}
