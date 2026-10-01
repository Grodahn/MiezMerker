import { registerSW } from 'virtual:pwa-register';

export interface OfflineShell { register(): void }
export const offlineShell: OfflineShell = {
  register() {
    registerSW({ immediate: true, onRegisterError: error => console.error('Offline shell registration failed', error) });
  },
};
