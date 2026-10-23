import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Marca KIPU: tres nudos de colores (kipu inca) y la palabra. */
@Component({
  selector: 'app-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="logo" role="img" aria-label="Kipu">
      <svg viewBox="0 0 28 24" width="26" height="22" aria-hidden="true">
        <rect x="3" y="2" width="3" height="20" rx="1.5" fill="#1e90ff" />
        <rect x="9" y="2" width="3" height="16" rx="1.5" fill="#f5b700" />
        <rect x="15" y="2" width="3" height="19" rx="1.5" fill="#22c55e" />
        <rect x="21" y="2" width="3" height="14" rx="1.5" fill="#a855f7" />
      </svg>
      <span class="logo-text">KIPU</span>
    </span>
  `,
})
export class LogoComponent {}
