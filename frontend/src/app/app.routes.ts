import { Routes } from '@angular/router';
import { activeGuard, entryGuard, guestGuard } from './core/guards';
import { LoginPage } from './features/auth/login.page';
import { ShellPage } from './features/app/shell.page';
import { OnboardingPage } from './features/onboarding/onboarding.page';
import { HistoryPage } from './features/payment/history.page';
import { PayPage } from './features/payment/pay.page';
import { ResultPage } from './features/payment/result.page';
import { ReviewPage } from './features/payment/review.page';

export const routes: Routes = [
  { path: '', pathMatch: 'full', canActivate: [entryGuard], children: [] },
  { path: 'login', component: LoginPage, canActivate: [guestGuard] },
  { path: 'onboarding', component: OnboardingPage },
  {
    path: 'app',
    component: ShellPage,
    canActivate: [activeGuard],
    children: [
      { path: '', component: PayPage },
      { path: 'review', component: ReviewPage },
      { path: 'result', component: ResultPage },
      { path: 'history', component: HistoryPage },
    ],
  },
  { path: '**', redirectTo: '' },
];
