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
      <div className="crash" role="alert">
        <div className="crash-card">
          <h2>Something broke</h2>
          <p>
            This page hit an error it could not recover from. Reloading usually clears it; if it
            keeps happening, the details below identify what went wrong.
          </p>
          <button className="btn-primary btn-block" onClick={() => window.location.reload()}>
            Reload
          </button>
          <div className="status-line is-error">{this.state.error.message}</div>
        </div>
      </div>
    );
  }
}
