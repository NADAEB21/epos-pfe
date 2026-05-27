import { Component, input } from '@angular/core';

@Component({
  selector: 'app-stub-page',
  standalone: true,
  template: `
    <div>
      <h1 class="text-2xl font-semibold text-gray-900 mb-2">{{ title() }}</h1>
      <p class="text-sm text-gray-500 mb-6">À implémenter dans une PR dédiée.</p>
      <div class="rounded-lg bg-white border border-gray-200 p-8 text-center text-gray-400 shadow-card">
        Design Figma : {{ figmaRef() }}
      </div>
    </div>
  `,
})
export class StubPageComponent {
  readonly title = input<string>('');
  readonly figmaRef = input<string>('');
}
