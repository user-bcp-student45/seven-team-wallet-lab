import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { messageOf } from '../../core/errors';
import { initials, normalizeAmount } from '../../core/format';
import { Contact } from '../../core/models';
import { PaymentFlowService } from '../../core/payment-flow.service';

/** Punto de entrada de pagos: elegir contacto y monto antes de revisar. */
@Component({
  selector: 'app-pay',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  template: `
    @if (welcome) {
      <p class="notice" role="status">Tu wallet está activa. Ya puedes pagar a tus contactos.</p>
    }

    <section class="qr-frame" aria-label="Escaneo de código QR">
      <div class="qr-box" aria-hidden="true"></div>
      <p class="muted small">El escaneo de QR llegará en una próxima versión. Elige un contacto.</p>
    </section>

    <form class="stack" [formGroup]="form" (ngSubmit)="continue()" novalidate>
      <fieldset class="stack">
        <legend class="muted">Pagar a</legend>
        @if (loading()) {
          <p class="muted">Cargando contactos…</p>
        } @else if (contacts().length === 0) {
          <p class="muted">Aún no tienes contactos para pagar.</p>
        }
        @for (c of contacts(); track c.id) {
          <label class="contact" [class.selected]="selected()?.id === c.id">
            <input type="radio" name="contact" [value]="c.id" [checked]="selected()?.id === c.id" (change)="selected.set(c)" />
            <span class="avatar" aria-hidden="true">{{ initialsOf(c.displayName) }}</span>
            <span class="contact-text">
              <span class="strong">{{ c.displayName }}</span>
              <span class="muted small">Celular {{ c.phoneMasked }}</span>
            </span>
          </label>
        }
      </fieldset>

      <div class="field">
        <label for="amount">Monto (S/)</label>
        <input id="amount" type="text" inputmode="decimal" formControlName="amount" autocomplete="off" placeholder="20.00"
               [class.invalid]="amountInvalid()" />
        @if (amountInvalid()) {
          <p class="field-error" role="alert">Ingresa un monto válido con hasta 2 decimales, por ejemplo 20.00.</p>
        }
      </div>

      @if (flow.error()) {
        <p class="alert" role="alert">{{ flow.error() }}</p>
      }
      @if (loadError()) {
        <p class="alert" role="alert">{{ loadError() }}</p>
      }

      <button class="btn btn-primary" type="submit" [disabled]="!canContinue() || flow.phase() === 'creating'">
        {{ flow.phase() === 'creating' ? 'Preparando…' : 'Continuar' }}
      </button>
    </form>
  `,
})
export class PayPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);
  protected readonly flow = inject(PaymentFlowService);
  protected readonly welcome = inject(ActivatedRoute).snapshot.queryParamMap.has('welcome');

  protected readonly contacts = signal<Contact[]>([]);
  protected readonly selected = signal<Contact | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    amount: ['', [Validators.required]],
  });
  private readonly amountValue = signal('');
  protected readonly canContinue = computed(() => !!this.selected() && normalizeAmount(this.amountValue()) !== null);

  async ngOnInit(): Promise<void> {
    this.form.controls.amount.valueChanges.subscribe((v) => this.amountValue.set(v));
    const previous = this.flow.intent();
    if (previous) {
      this.form.controls.amount.setValue(previous.amount);
      this.selected.set(this.flow.contact());
    }
    try {
      this.contacts.set(await firstValueFrom(this.api.contacts()));
    } catch (error) {
      this.loadError.set(messageOf(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected initialsOf(name: string): string {
    return initials(name);
  }

  protected amountInvalid(): boolean {
    const control = this.form.controls.amount;
    return control.dirty && normalizeAmount(control.value) === null;
  }

  protected async continue(): Promise<void> {
    const contact = this.selected();
    const amount = normalizeAmount(this.form.controls.amount.value);
    if (!contact || !amount) {
      return;
    }
    if (await this.flow.prepare(contact, amount)) {
      await this.router.navigate(['/app/review']);
    }
  }
}
