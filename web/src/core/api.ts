import type { ApiErrorBody } from './errors';
import { ApiError } from './errors';
import { isBlobId } from './safe';

/** Thin fetch wrapper for /api/v1 with the session's bearer token. */
let token: string | null = null;
let onUnauthorized: (() => void) | null = null;

export function setToken(value: string | null) {
  token = value;
}

export function getToken() {
  return token;
}

export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

/**
 * Paths are built from ids, and some ids arrive inside other people's posts. One shaped like `../auth/logout` must
 * never get to choose the endpoint, so anything that isn't a plain path stops here.
 */
function plainPath(path: string): boolean {
  const [route, query = ''] = path.split('?', 2);
  return /^(\/[A-Za-z0-9_-]+)+$/.test(route) && /^[A-Za-z0-9_=&.-]*$/.test(query);
}

async function request<T>(method: string, path: string, body?: unknown, init?: RequestInit): Promise<T> {
  if (!plainPath(path)) throw new ApiError(400, 'bad_request', 'That link is not valid');
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined && !(body instanceof Blob) && !(body instanceof Uint8Array)) headers['Content-Type'] = 'application/json';
  const response = await fetch(`/api/v1${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : body instanceof Blob || body instanceof Uint8Array ? (body as BodyInit) : JSON.stringify(body),
    ...init,
  });
  if (!response.ok) {
    let parsed: ApiErrorBody = { error: 'http_' + response.status, message: response.statusText || 'Request failed' };
    try {
      parsed = await response.json();
    } catch {
      /* not JSON */
    }
    if (response.status === 401 && token) onUnauthorized?.();
    throw new ApiError(response.status, parsed.error, parsed.message, parsed.command ?? null);
  }
  if (response.status === 204) return undefined as T;
  const type = response.headers.get('Content-Type') ?? '';
  return (type.includes('application/json') ? response.json() : response.arrayBuffer()) as Promise<T>;
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  patch: <T>(path: string, body: unknown) => request<T>('PATCH', path, body),
  delete: <T>(path: string) => request<T>('DELETE', path),
  raw: request,
};

export async function fetchBlob(blobId: string, range?: [number, number]): Promise<Uint8Array> {
  if (!isBlobId(blobId)) throw new ApiError(400, 'blob', 'Media not available');
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (range) headers.Range = `bytes=${range[0]}-${range[1] - 1}`;
  const response = await fetch(`/api/v1/blobs/${blobId}`, { headers });
  if (!response.ok) throw new ApiError(response.status, 'blob', 'Media not available');
  return new Uint8Array(await response.arrayBuffer());
}
