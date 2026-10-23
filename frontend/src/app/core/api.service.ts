import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Contact,
  Intent,
  Movement,
  Onboarding,
  Payment,
  PhoneCode,
  SessionUser,
  Wallet,
} from './models';

/** Cliente del BFF. Solo rutas relativas del mismo origen; sin tokens en el navegador. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  me(): Observable<SessionUser> {
    return this.http.get<SessionUser>('/api/auth/me');
  }

  login(email: string, password: string): Observable<SessionUser> {
    return this.http.post<SessionUser>('/api/auth/login', { email, password });
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', {});
  }

  register(email: string, password: string): Observable<Onboarding> {
    return this.http.post<Onboarding>('/api/onboarding/register', { email, password });
  }

  onboarding(): Observable<Onboarding> {
    return this.http.get<Onboarding>('/api/onboarding');
  }

  savePersonalData(fullName: string, documentNumber: string): Observable<Onboarding> {
    return this.http.post<Onboarding>('/api/onboarding/personal-data', { fullName, documentNumber });
  }

  requestPhoneCode(phone: string): Observable<PhoneCode> {
    return this.http.post<PhoneCode>('/api/onboarding/phone/code', { phone });
  }

  verifyPhone(code: string): Observable<Onboarding> {
    return this.http.post<Onboarding>('/api/onboarding/phone/verify', { code });
  }

  activate(): Observable<Onboarding> {
    return this.http.post<Onboarding>('/api/onboarding/activate', { acceptTerms: true });
  }

  contacts(): Observable<Contact[]> {
    return this.http.get<Contact[]>('/api/contacts');
  }

  wallet(): Observable<Wallet> {
    return this.http.get<Wallet>('/api/wallet');
  }

  createIntent(contactId: string, amount: string): Observable<Intent> {
    return this.http.post<Intent>('/api/payments/intents', { contactId, amount });
  }

  /** La clave de idempotencia pertenece al intento y se conserva en todos los reintentos. */
  confirm(intentId: string, idempotencyKey: string): Observable<Payment> {
    return this.http.post<Payment>(
      '/api/payments',
      { intentId },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    );
  }

  intentResult(intentId: string): Observable<Payment> {
    return this.http.get<Payment>(`/api/payments/intents/${intentId}/result`);
  }

  history(page = 0, size = 20): Observable<Movement[]> {
    return this.http.get<Movement[]>('/api/payments', { params: { page, size } });
  }
}
