import { Component, type ErrorInfo, type ReactNode } from 'react';

/**
 * Keeps one broken piece from taking the page with it: a message whose content isn't what the app expects (a modified
 * client can send anything, encrypted) shows [fallback] instead of breaking the whole chat for everyone in it.
 */
export class Guard extends Component<{ children: ReactNode; fallback?: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.warn('Could not show this part of the page', error, info.componentStack);
  }

  render() {
    return this.state.failed ? (this.props.fallback ?? null) : this.props.children;
  }
}
