import { Component, type ErrorInfo, type ReactNode } from 'react';

/**
 * Without this, any render-time throw unmounts the whole tree and leaves a blank
 * page with nothing but a console message — which is what a single unexpected
 * field in an API response used to produce.
 */
export class ErrorBoundary extends Component<
  { children: ReactNode },
  { error: Error | null }
> {
  override state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unhandled render error', error, info.componentStack);
  }

  override render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="card" role="alert">
        <div className="card-head">
          <h2>Something broke</h2>
        </div>
        <p className="card-desc">
          This page hit an error it could not recover from. Reloading usually clears it; if it
          keeps happening, the details below identify what went wrong.
        </p>
        <div className="log-panel">{this.state.error.message}</div>
        <button className="btn-primary" onClick={() => window.location.reload()}>
          Reload
        </button>
      </div>
    );
  }
}
