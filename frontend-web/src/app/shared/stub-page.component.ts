import { Component, input } from '@angular/core';

@Component({
  selector: 'app-stub-page',
  standalone: true,
  templateUrl: './stub-page.component.html',
})
export class StubPageComponent {
  readonly title = input<string>('');
  readonly figmaRef = input<string>('');
}
