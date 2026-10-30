import { create } from 'zustand';
import type { AccountKeys } from '../crypto/account';
import type { Pin } from './db';
import type { Me, ObjectDto, ThreadDto, UserDto } from './types';

/** A decrypted, verified object. */
export interface Entity<P = unknown> {
  dto: ObjectDto;
  key: Uint8Array;
  payload: P;
}

export interface Toast {
  id: number;
  text: string;
  tone?: 'default' | 'error' | 'success';
}

export interface AppState {
  phase: 'loading' | 'signedOut' | 'ready';
  me: Me | null;
  keys: AccountKeys | null;
  users: Record<string, UserDto>;
  entities: Record<string, Entity>;
  threads: Record<string, ThreadDto>;
  pins: Record<string, Pin>;
  followRequests: string[];
  typing: Record<string, Record<string, number>>;
  presence: Record<string, boolean>;
  seenStories: Record<string, number>;
  connection: 'online' | 'connecting' | 'offline';
  syncing: boolean;
  initialSyncDone: boolean;
  toasts: Toast[];
}

const initial: AppState = {
  phase: 'loading',
  me: null,
  keys: null,
  users: {},
  entities: {},
  threads: {},
  pins: {},
  followRequests: [],
  typing: {},
  presence: {},
  seenStories: {},
  connection: 'connecting',
  syncing: false,
  initialSyncDone: false,
  toasts: [],
};

export const useApp = create<AppState>()(() => ({ ...initial }));

export const resetApp = (phase: AppState['phase']) => useApp.setState({ ...initial, phase });

let toastId = 0;
export function toast(text: string, tone: Toast['tone'] = 'default') {
  const id = ++toastId;
  useApp.setState((s) => ({ toasts: [...s.toasts, { id, text, tone }] }));
  setTimeout(() => useApp.setState((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })), 3800);
}

export const getState = () => useApp.getState();
export const meId = () => useApp.getState().me?.id ?? '';
