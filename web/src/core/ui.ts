import { create } from 'zustand';

/** Purely presentational app state (open sheets, theme). */
export type CreateKind = 'post' | 'story' | 'reel' | 'note' | null;

interface UiState {
  create: CreateKind;
  createMenu: boolean;
  theme: 'system' | 'dark' | 'light';
}

function initialTheme(): UiState['theme'] {
  try {
    return (localStorage.getItem('aezgram.theme') as UiState['theme']) || 'system';
  } catch {
    return 'system';
  }
}

export const useUi = create<UiState>()(() => ({ create: null, createMenu: false, theme: initialTheme() }));

export function applyTheme(theme: UiState['theme']) {
  if (theme === 'system') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', theme);
  try {
    localStorage.setItem('aezgram.theme', theme);
  } catch {
    /* private mode */
  }
  useUi.setState({ theme });
}

export const openCreate = (kind: CreateKind) => useUi.setState({ create: kind, createMenu: false });
