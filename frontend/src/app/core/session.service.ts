import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiService } from './api.service';
import { SessionUser } from './models';

/** Estado de sesion en memoria; el servidor mantiene la sesion y no se guarda nada en el navegador. */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly api = inject(ApiService);

  readonly user = signal<SessionUser | null>(null);
  readonly balance = signal<string | null>(null);
  readonly isActive = computed(() => this.user()?.status === 'ACTIVE');

  async init(): Promise<void> {
    try {
      this.user.set(await firstValueFrom(this.api.me()));
    } catch {
      this.user.set(null);
    }
  }

  async login(email: string, password: string): Promise<SessionUser> {
    const user = await firstValueFrom(this.api.login(email, password));
    this.user.set(user);
    return user;
  }

  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.api.logout());
    } finally {
      this.clear();
    }
  }

  clear(): void {
    this.user.set(null);
    this.balance.set(null);
  }

  async refreshUser(): Promise<void> {
    await this.init();
  }

  async refreshBalance(): Promise<void> {
    try {
      this.balance.set((await firstValueFrom(this.api.wallet())).balance);
    } catch {
      this.balance.set(null);
    }
  }
}
