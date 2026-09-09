import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { getMe, listPending, login, logout } from './api';
import { Investigate } from './Investigate';
import { Operate } from './Operate';
import { ReportsQueue } from './Reports';
import { Review } from './Review';
import { ToastProvider } from './ui';

type Surface = 'review' | 'reports' | 'investigate' | 'operate';

export function App() {
  // Prefilled only under `vite dev`; a production bundle opens with an empty field.
  const [email, setEmail] = useState(import.meta.env.DEV ? 'admin@example.com' : '');
  const [password, setPassword] = useState('');
  const [status, setStatus] = useState('');
  const [busy, setBusy] = useState(false);
  const queryClient = useQueryClient();

  // The session cookie survives a page refresh even though React state does
  // not — checked once on load so a reload is not mistaken for a sign-out.
  const me = useQuery({ queryKey: ['me'], queryFn: getMe, retry: false });

  if (me.isPending) {
    return (
      <div className="auth">
        <Brand />
      </div>
    );
  }

  if (!me.data) {
    return (
      <div className="auth">
        <Brand />
        <section className="panel">
          <div className="panel-body">
            {/* The role-granting SQL is a local-development aid, not something to
                print on a deployed sign-in page. */}
            {import.meta.env.DEV ? (
              <p className="section-note">
                There is no self-service admin registration. Grant the role directly in Postgres for local testing:{' '}
                <code>UPDATE account.account SET roles = 'USER,ADMIN' WHERE email = …</code>
              </p>
            ) : (
              <p className="section-note">Sign in with an account that carries the ADMIN role.</p>
            )}

            <label className="field">
              <span className="field-label">Email</span>
              <input value={email} autoComplete="username" onChange={(e) => setEmail(e.target.value)} />
            </label>

            <label className="field">
              <span className="field-label">Password</span>
              <input
                type="password"
                value={password}
                autoComplete="current-password"
                onChange={(e) => setPassword(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !busy) void signIn();
                }}
              />
            </label>

            <div className="btn-row">
              <button className="btn-accent" disabled={busy} onClick={() => void signIn()}>
                {busy ? 'Signing in…' : 'Sign in'}
              </button>
            </div>

            {status && <p className="error-text">{status}</p>}
          </div>
        </section>
      </div>
    );
  }

  if (!me.data.roles.includes('ADMIN')) {
    return (
      <div className="auth">
        <Brand />
        <section className="panel">
          <div className="panel-body">
            <p className="error-text">
              Signed in as {me.data.displayName}, but this account does not carry the ADMIN role.
            </p>
            <div className="btn-row">
              <button onClick={() => void signOut()}>Sign out</button>
            </div>
          </div>
        </section>
      </div>
    );
  }

  return (
    <ToastProvider>
      <Console displayName={me.data.displayName} onSignOut={() => void signOut()} />
    </ToastProvider>
  );

  async function signIn() {
    try {
      setBusy(true);
      setStatus('');
      await login(email, password);
      await queryClient.invalidateQueries({ queryKey: ['me'] });
    } catch (error) {
      setStatus(`Sign in failed: ${(error as Error).message}`);
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    await logout();
    // resetQueries, not removeQueries: the latter only drops the cache entry
    // and leaves an already-mounted observer holding a stale reference, so the
    // app never re-renders back to the signed-out state.
    await queryClient.resetQueries({ queryKey: ['me'] });
  }
}

function Brand() {
  return (
    <div className="brand">
      <span className="brand-mark">M</span>
      <span>Moderation Console</span>
    </div>
  );
}

function Console({ displayName, onSignOut }: { displayName: string; onSignOut: () => void }) {
  const [surface, setSurface] = useState<Surface>('review');

  // Shares a cache key with Operate, so the badge costs nothing extra.
  const backlog = useQuery({
    queryKey: ['queue-health'],
    queryFn: () => listPending(undefined, 100),
    refetchInterval: 20000,
  });

  const waiting = backlog.data?.items.length ?? 0;
  const capped = Boolean(backlog.data?.nextCursor);

  return (
    <div className="shell">
      <header className="topbar">
        <Brand />

        <nav className="nav">
          <button aria-current={surface === 'review' ? 'page' : undefined} onClick={() => setSurface('review')}>
            Review
            {waiting > 0 && (
              <span className={`badge${capped || waiting > 25 ? ' is-warn' : ''}`}>
                {waiting}
                {capped ? '+' : ''}
              </span>
            )}
          </button>
          <button aria-current={surface === 'reports' ? 'page' : undefined} onClick={() => setSurface('reports')}>
            Reports
          </button>
          <button aria-current={surface === 'investigate' ? 'page' : undefined} onClick={() => setSurface('investigate')}>
            Investigate
          </button>
          <button aria-current={surface === 'operate' ? 'page' : undefined} onClick={() => setSurface('operate')}>
            Operate
          </button>
        </nav>

        <div className="topbar-end">
          <span className="who">{displayName}</span>
          <button className="btn-quiet" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      </header>

      <main className="surface">
        {surface === 'review' && <Review />}
        {surface === 'reports' && <ReportsQueue />}
        {surface === 'investigate' && <Investigate />}
        {surface === 'operate' && <Operate />}
      </main>
    </div>
  );
}
