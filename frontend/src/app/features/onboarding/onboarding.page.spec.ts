import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { OnboardingPage } from './onboarding.page';

describe('OnboardingPage (paso 1)', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [OnboardingPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('muestra el paso actual y bloquea el avance con datos invalidos', async () => {
    const fixture = TestBed.createComponent(OnboardingPage);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('Paso 1 de 4 · Cuenta');
    expect(el.querySelectorAll('.progress-seg.on').length).toBe(1);

    const submit = el.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(submit.disabled).toBe(true);

    const email = el.querySelector('#email') as HTMLInputElement;
    email.value = 'no-es-correo';
    email.dispatchEvent(new Event('input'));
    email.dispatchEvent(new Event('blur'));
    await fixture.whenStable();
    expect(el.textContent).toContain('Ingresa un correo válido');
    expect(submit.disabled).toBe(true);
  });
});
