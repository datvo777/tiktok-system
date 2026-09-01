import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { ApiError, getMe, login, logout, refreshSession, register } from './api';
import { Feed } from './Feed';
import { Notifications } from './Notifications';
import { SearchPanel } from './SearchPanel';
import { Upload } from './Upload';

export function App() {
  const [email, setEmail] = useState('creator@example.com');
  const [password, setPassword] = useState('correct-horse-battery');
  const [status, setStatus] = useState<string>('');
  const queryClient = useQueryClient();

  // The session cookie survives a page refresh even though React state
  // doesn't -- check it once on load instead of always showing "Not signed
  // in" right after a reload of an otherwise still-valid session.
  const me = useQuery({ queryKey: ['me'], queryFn: getMe, retry: false });
  const signedIn = !!me.data;

  useEffect(() => {
    if (me.isPending) return;
    setStatus(me.data ? `Signed in as ${me.data.displayName}.` : 'Not signed in.');
  }, [me.isPending, me.data]);

  /**
   * A 401 mid-session means the token expired or was revoked (a logout
   * elsewhere, or an account suspension). Clearing the `me` query is what flips
   * the UI to signed-out; previously the app kept claiming "Signed in" while
   * every request failed, and the polling panels kept retrying against a dead
   * session.
   */
  useEffect(() => {
    if (me.error instanceof ApiError && me.error.isUnauthenticated) {
      queryClient.setQueryData(['me'], null);
    }
  }, [me.error, queryClient]);

  /**
   * Extends the session periodically while the tab is in use, so a 30-minute TTL
   * does not sign an active user out mid-task. Well inside the TTL, and it stops
   * on the first failure rather than retrying — a refusal means the session is
   * genuinely over (revoked, or the account suspended), and hammering it would
   * only turn one dead session into repeated failed requests.
   */
  useEffect(() => {
    if (!signedIn) return;
    const timer = setInterval(
      () => {
        void refreshSession().catch(() => queryClient.setQueryData(['me'], null));
      },
      10 * 60 * 1000,
    );
    return () => clearInterval(timer);
  }, [signedIn, queryClient]);

  async function run(label: string, action: () => Promise<string>) {
    try {
      setStatus(`${label}...`);
      const message = await action();
      await queryClient.invalidateQueries({ queryKey: ['me'] });
      setStatus(message);
    } catch (error) {
      setStatus(`${label} failed: ${(error as Error).message}`);
    }
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand">
          <span className="brand-mark">▶</span>
          Short Video
        </div>
        <span className="session-chip">
          <span className={`session-dot${signedIn ? ' on' : ''}`} />
          {signedIn ? 'Signed in' : 'Not signed in'}
        </span>
      </header>

      <section className="card">
        <div className="card-head">
          <h2>Account</h2>
          <span className="card-eyebrow">Milestone 1</span>
        </div>
        <p className="card-desc">Registration, login, upload, transcoding, and owner-preview playback.</p>

        <label className="field">
          <span className="field-label">Email</span>
          <input value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>

        <label className="field">
          <span className="field-label">Password (12+ characters)</span>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>

        <div className="btn-row" style={{ marginTop: '1rem' }}>
          <button
            onClick={() =>
              run('Register', async () => {
                await register(email, password, 'Local Creator');
                return 'Registered. Now sign in.';
              })
            }
          >
            Register
          </button>

          <button
            className="btn-primary"
            onClick={() =>
              run('Login', async () => {
                await login(email, password);
                return 'Signed in. Session cookie set (HttpOnly, so JS cannot read it).';
              })
            }
          >
            Log in
          </button>

          <button
            className="btn-ghost"
            onClick={() =>
              run('Logout', async () => {
                await logout();
                // Logout revokes the token server-side, so anything cached for
                // the old session is now unreachable as well as stale.
                queryClient.clear();
                return 'Signed out; session cookie cleared and token revoked.';
              })
            }
          >
            Log out
          </button>
        </div>

        <div className="log-panel">{status}</div>
      </section>

      {/*
        Rendered only when signed in. These panels poll and fetch on mount, so
        showing them to a signed-out visitor produced a burst of 401s on first
        paint and every 10 seconds thereafter.
      */}
      {signedIn ? (
        <>
          <Upload />
          <Feed />
          <SearchPanel />
          <Notifications />
        </>
      ) : (
        <section className="card">
          <div className="card-head">
            <h2>Feed, upload, search and notifications</h2>
          </div>
          <p className="card-desc">Sign in to load them.</p>
        </section>
      )}
    </div>
  );
}
