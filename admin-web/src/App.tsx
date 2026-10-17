import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ApiError, getMe, getMfaStatus, listPending, login, logout, verifyMfa } from './api';
import { Investigate } from './Investigate';
import { MfaGate } from './MfaGate';
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
  // Set once the password was right and a code is still needed; the sign-in has no session until it is given.
  const [pendingToken, setPendingToken] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [useRecovery, setUseRecovery] = useState(false);
  const queryClient = useQueryClient();

  // The session cookie survives a page refresh even though React state does
  // not — checked once on load so a reload is not mistaken for a sign-out.
  const me = useQuery({ queryKey: ['me'], queryFn: getMe, retry: false });

  // Asked only once signed in. Null means the server has the second factor switched off.
  const mfa = useQuery({
    queryKey: ['mfa-status'],
    queryFn: getMfaStatus,
    retry: false,
    enabled: Boolean(me.data?.roles.includes('ADMIN')),
  });

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
                There is no self-service admin registration. Grant the role directly in Postgres for local
                testing: <code>UPDATE account.account SET roles = 'USER,ADMIN' WHERE email = …</code>
              </p>
            ) : (
              <p className="section-note">Sign in with an account that carries the ADMIN role.</p>
            )}

            {pendingToken ? (
              <>
                <p className="section-note">
                  {useRecovery
                    ? 'Enter one of your recovery codes. Each works once.'
                    : 'Enter the six-digit code from your authenticator app.'}
                </p>
                <label className="field">
                  <span className="field-label">{useRecovery ? 'Recovery code' : 'Code'}</span>
                  <input
                    value={code}
                    inputMode={useRecovery ? 'text' : 'numeric'}
                    autoComplete="one-time-code"
                    autoFocus
                    onChange={(e) => setCode(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && !busy && code) void submitCode();
                    }}
                  />
                </label>
                <div className="btn-row">
                  <button className="btn-accent" disabled={busy || !code} onClick={() => void submitCode()}>
                    {busy ? 'Checking…' : 'Verify'}
                  </button>
                  <button
                    onClick={() => {
                      setUseRecovery(!useRecovery);
                      setCode('');
                      setStatus('');
                    }}
                  >
                    {useRecovery ? 'Use the app instead' : 'Use a recovery code'}
                  </button>
                  <button onClick={cancelCode}>Start over</button>
                </div>
              </>
            ) : (
              <>
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
              </>
            )}

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

  // The server shuts /internal to a sign-in without a second factor, so the console would only show errors.
  // Wait for the answer rather than flash the console first.
  if (mfa.isPending) {
    return (
      <div className="auth">
        <Brand />
      </div>
    );
  }
  if (mfa.data && mfa.data.required && !mfa.data.signedInWithSecondFactor) {
    return (
      <div className="auth">
        <Brand />
        <MfaGate enrolled={mfa.data.enrolled} onSignOut={() => void signOut()} />
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
      const result = await login(email, password);
      // Not kept in state once it has been used, whether or not a code is still to come.
      setPassword('');
      if (result.kind === 'mfa-required') {
        setPendingToken(result.mfaToken);
        return;
      }
      await queryClient.invalidateQueries({ queryKey: ['me'] });
    } catch (error) {
      setStatus(`Sign in failed: ${(error as Error).message}`);
    } finally {
      setBusy(false);
    }
  }

  async function submitCode() {
    if (!pendingToken) return;
    try {
      setBusy(true);
      setStatus('');
      await verifyMfa(pendingToken, useRecovery ? { recoveryCode: code.trim() } : { code: code.trim() });
      setPendingToken(null);
      setCode('');
      await queryClient.invalidateQueries({ queryKey: ['me'] });
    } catch (error) {
      // A pending sign-in that is spent or expired cannot be retried: the password has to be entered again.
      if (error instanceof ApiError && error.code === 'MFA_TOKEN_INVALID') {
        cancelCode();
      }
      setStatus((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function cancelCode() {
    setPendingToken(null);
    setCode('');
    setUseRecovery(false);
    setStatus('');
  }

  async function signOut() {
    await logout();
    // resetQueries, not removeQueries: the latter only drops the cache entry
    // and leaves an already-mounted observer holding a stale reference, so the
    // app never re-renders back to the signed-out state.
    await queryClient.resetQueries({ queryKey: ['me'] });
    // After `me`, so the signed-out view is already showing and the status query is disabled: it would
    // otherwise refetch with the cookie just cleared and answer 401.
    await queryClient.resetQueries({ queryKey: ['mfa-status'] });
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
          <button
            aria-current={surface === 'review' ? 'page' : undefined}
            onClick={() => setSurface('review')}
          >
            Review
            {waiting > 0 && (
              <span className={`badge${capped || waiting > 25 ? ' is-warn' : ''}`}>
                {waiting}
                {capped ? '+' : ''}
              </span>
            )}
          </button>
          <button
            aria-current={surface === 'reports' ? 'page' : undefined}
            onClick={() => setSurface('reports')}
          >
            Reports
          </button>
          <button
            aria-current={surface === 'investigate' ? 'page' : undefined}
            onClick={() => setSurface('investigate')}
          >
            Investigate
          </button>
          <button
            aria-current={surface === 'operate' ? 'page' : undefined}
            onClick={() => setSurface('operate')}
          >
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
