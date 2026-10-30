import { getToken } from './api';
import { useApp } from './store';
import type { WsFrame } from './types';

type Handler = (frame: WsFrame) => void;

/**
 * One WebSocket per tab. The token travels as a subprotocol (browsers cannot set headers on WebSockets), so it never
 * appears in a URL or a log line. Reconnects with backoff; pings keep Cloudflare's idle timer happy.
 */
class Realtime {
  private socket: WebSocket | null = null;
  private handlers = new Set<Handler>();
  private retry = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private ping: ReturnType<typeof setInterval> | null = null;
  private wanted = false;

  connect() {
    this.wanted = true;
    this.open();
    window.addEventListener('online', this.reconnectNow);
    document.addEventListener('visibilitychange', this.onVisible);
  }

  close() {
    this.wanted = false;
    window.removeEventListener('online', this.reconnectNow);
    document.removeEventListener('visibilitychange', this.onVisible);
    if (this.timer) clearTimeout(this.timer);
    if (this.ping) clearInterval(this.ping);
    this.socket?.close();
    this.socket = null;
  }

  on(handler: Handler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  send(frame: WsFrame): boolean {
    if (this.socket?.readyState !== WebSocket.OPEN) return false;
    this.socket.send(JSON.stringify(frame));
    return true;
  }

  get isOpen() {
    return this.socket?.readyState === WebSocket.OPEN;
  }

  private reconnectNow = () => {
    if (!this.wanted || this.isOpen) return;
    this.retry = 0;
    this.open();
  };

  private onVisible = () => {
    if (document.visibilityState === 'visible') this.reconnectNow();
  };

  private open() {
    const token = getToken();
    if (!token || !this.wanted) return;
    if (this.timer) clearTimeout(this.timer);
    this.socket?.close();
    useApp.setState({ connection: 'connecting' });
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new WebSocket(`${scheme}://${location.host}/api/v1/ws`, ['aezgram.v1', `auth.${token}`]);
    this.socket = socket;
    socket.onopen = () => {
      this.retry = 0;
      useApp.setState({ connection: 'online' });
      if (this.ping) clearInterval(this.ping);
      this.ping = setInterval(() => this.send({ t: 'ping' }), 25_000);
    };
    socket.onmessage = (event) => {
      let frame: WsFrame;
      try {
        frame = JSON.parse(event.data);
      } catch {
        return;
      }
      this.handlers.forEach((h) => h(frame));
    };
    socket.onclose = () => {
      if (this.socket !== socket) return;
      if (this.ping) clearInterval(this.ping);
      useApp.setState({ connection: navigator.onLine ? 'connecting' : 'offline' });
      if (!this.wanted) return;
      const delay = Math.min(30_000, 1000 * 2 ** this.retry++) * (0.75 + Math.random() / 2);
      this.timer = setTimeout(() => this.open(), delay);
    };
  }
}

export const realtime = new Realtime();
