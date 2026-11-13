import { useEffect, useState } from 'react';
import { create } from 'zustand';

/** Purely presentational app state (open sheets, theme). */
export type CreateKind = 'post' | 'story' | 'reel' | 'note' | null;

interface UiState {
  create: CreateKind;
  createMenu: boolean;
  theme: 'system' | 'dark' | 'light';
  /** The desktop sidebar folded to icons (it opens over the page on hover), unless kept open. Remembered here. */
  navCollapsed: boolean;
  /** Signed in with a password the rules would refuse today: a banner asks for a better one until it changes. */
  weakPassword: boolean;
}

function initialWeak(): boolean {
  try {
    return localStorage.getItem('hush.weakPassword') === '1';
  } catch {
    return false;
  }
}

export function setWeakPassword(weak: boolean) {
  try {
    if (weak) localStorage.setItem('hush.weakPassword', '1');
    else localStorage.removeItem('hush.weakPassword');
  } catch {
    /* private mode */
  }
  useUi.setState({ weakPassword: weak });
}

function initialNav(): boolean {
  try {
    return localStorage.getItem('hush.nav') !== 'open';
  } catch {
    return true;
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

export const useUi = create<UiState>()(() => ({
  create: null,
  createMenu: false,
  theme: initialTheme(),
  navCollapsed: initialNav(),
  weakPassword: initialWeak(),
}));

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

/** Whether the app is showing its dark appearance right now (the user's choice, or the system's on Auto). */
export function useDarkMode(): boolean {
  const theme = useUi((s) => s.theme);
  const [systemDark, setSystemDark] = useState(() => matchMedia('(prefers-color-scheme: dark)').matches);
  useEffect(() => {
    const query = matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => setSystemDark(query.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);
  return theme === 'dark' || (theme === 'system' && systemDark);
}

export const openCreate = (kind: CreateKind) => useUi.setState({ create: kind, createMenu: false });
