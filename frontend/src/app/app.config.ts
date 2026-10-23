import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { SessionService } from './core/session.service';
import { unauthorizedInterceptor } from './core/unauthorized.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // XSRF: Angular lee la cookie XSRF-TOKEN y envia X-XSRF-TOKEN en las escrituras del mismo origen.
    provideHttpClient(withInterceptors([unauthorizedInterceptor])),
    provideAppInitializer(() => inject(SessionService).init()),
  ],
};
