import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { messageOf } from '../../core/errors';
import { formatDateTime } from '../../core/format';
import { Movement } from '../../core/models';

/** Historial del usuario: movimientos enviados y recibidos con su referencia verificable. */
@Component({
  selector: 'app-history',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="title">Historial</h1>
    @if (error()) {
      <p class="alert" role="alert">{{ error() }}</p>
    } @else if (loading()) {
      <p class="muted">Cargando movimientos…</p>
    } @else if (items().length === 0) {
      <p class="muted">Todavía no tienes movimientos.</p>
    }
    <ul class="list">
      @for (m of items(); track m.paymentId) {
        <li class="list-item">
          <div class="contact-text">
            <span class="strong">{{ m.direction === 'SENT' ? 'Pago a ' : 'Recibido de ' }}{{ m.counterpartName }}</span>
            <span class="muted small">{{ when(m.createdAt) }} · <span class="mono">{{ m.reference }}</span></span>
            @if (m.status !== 'SUCCESS') {
              <span class="badge badge-warn">No realizado · sin cobro</span>
            }
          </div>
          <span class="strong" [class.positive]="m.direction === 'RECEIVED' && m.status === 'SUCCESS'"
                [class.struck]="m.status !== 'SUCCESS'">
            {{ sign(m) }}S/ {{ m.amount }}
          </span>
        </li>
      }
    </ul>
  `,
})
export class HistoryPage implements OnInit {
  private readonly api = inject(ApiService);

  protected readonly items = signal<Movement[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    try {
      this.items.set(await firstValueFrom(this.api.history()));
    } catch (error) {
      this.error.set(messageOf(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected when(iso: string): string {
    return formatDateTime(iso);
  }

  protected sign(m: Movement): string {
    if (m.status !== 'SUCCESS') {
      return '';
    }
    return m.direction === 'SENT' ? '- ' : '+ ';
  }
}
