import { pwaBrandAssets } from './assets/pwa';
import logo from './assets/branding/logo.png';
import home from './assets/illustrations/home.svg';
import background from './assets/illustrations/background.svg';
import cat from './assets/placeholders/cat.svg';
import feedingSite from './assets/placeholders/feeding-site.svg';
import bowl from './assets/placeholders/bowl.svg';
import homeIcon from './assets/icons/home.svg';
import sync from './assets/icons/sync.svg';
import pin from './assets/icons/pin.svg';
import catIcon from './assets/icons/cat.svg';
import bluetooth from './assets/icons/bluetooth.svg';
import success from './assets/icons/success.svg';
import error from './assets/icons/error.svg';
import offline from './assets/icons/offline.svg';
import arrow from './assets/icons/arrow.svg';
import account from './assets/icons/account.svg';
import logout from './assets/icons/logout.svg';
import info from './assets/icons/info.svg';

// Single artwork adapter: screens use semantic references, never filenames.
// Imports keep fallbacks in Vite's dependency graph and Workbox precache.
export const assets = {
  brand: { logo, ...pwaBrandAssets },
  illustrations: { background, home, loginBackground: background, sync: bluetooth, syncSuccess: success, error, offline },
  placeholders: { cat, feedingSite, node: bowl, bowl, generic: feedingSite },
  icons: { home: homeIcon, sync, feedingSite: pin, cat: catIcon, bluetooth, success, error, offline, arrow, account, logout },
  status: { loading: sync, empty: feedingSite, error, offline, success, info, bluetooth, cat, node: bowl, feedingSite },
};
export type IconName = keyof typeof assets.icons;
