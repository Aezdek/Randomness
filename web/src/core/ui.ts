import { create } from 'zustand';

/** Purely presentational app state (open sheets, theme). */
export type CreateKind = 'post' | 'story' | 'reel' | 'note' | null;

interface UiState {
  create: CreateKind;
  createMenu: boolean;
  theme: 'system' | 'dark' | 'light';
  /** The desktop sidebar folded to icons (it opens over the page on hover). Remembered on this browser. */
  navCollapsed: boolean;
}

function initialNav(): boolean {
  try {
    return localStorage.getItem('hush.nav') === 'collapsed';
  } catch {
    return false;
  }
}

export function setNavCollapsed(collapsed: boolean) {
  try {
    localStorage.setItem('hush.nav', collapsed ? 'collapsed' : 'open');
  } catch {
    /* private mode */
  }
  useUi.setState({ navCollapsed: collapsed });
}

function initialTheme(): UiState['theme'] {
  try {
    return (localStorage.getItem('hush.theme') as UiState['theme']) || 'system';
  } catch {
    return 'system';
  }
}

export const useUi = create<UiState>()(() => ({ create: null, createMenu: false, theme: initialTheme(), navCollapsed: initialNav() }));

export function applyTheme(theme: UiState['theme']) {
  if (theme === 'system') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', theme);
  try {
    localStorage.setItem('hush.theme', theme);
  } catch {
    /* private mode */
  }
  useUi.setState({ theme });
}

export const openCreate = (kind: CreateKind) => useUi.setState({ create: kind, createMenu: false });
