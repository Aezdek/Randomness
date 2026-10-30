import { useEffect } from 'react';
import { api } from './api';
import type { SettingsPayload } from './payloads';
import { useIndex } from './selectors';
import { useApp } from './store';

/** Push types each "Notify me about" switch covers. Test rings always ring. */
const TYPES: [keyof SettingsPayload, string[]][] = [
  ['notifyMessages', ['message', 'reaction', 'call']],
  ['notifyComments', ['comment', 'activity']],
  ['notifyLikes', ['like']],
  ['notifyFollows', ['follow', 'follow_request', 'follow_accepted']],
  ['notifyStories', ['answer']],
];

export function pushPrefsOf(settings: SettingsPayload | undefined) {
  return {
    mutedTypes: TYPES.filter(([key]) => settings?.[key] === false).flatMap(([, types]) => types),
    mutedThreads: settings?.mutedThreads ?? [],
  };
}

/**
 * The switches live in the encrypted settings object, but a Web Push must always show something, so muted pushes
 * must not be sent at all. Each change is mirrored to the server as a list of push types and thread ids.
 */
export function usePushPrefsMirror() {
  const settings = useIndex().settings?.payload as SettingsPayload | undefined;
  const me = useApp((s) => s.me?.id);
  const key = JSON.stringify(pushPrefsOf(settings));
  useEffect(() => {
    const slot = `aezgram.pushPrefs.${me}`;
    if (!me || sessionStorage.getItem(slot) === key) return;
    api
      .put('/push/prefs', JSON.parse(key))
      .then(() => sessionStorage.setItem(slot, key))
      .catch(() => undefined);
  }, [key, me]);
}
