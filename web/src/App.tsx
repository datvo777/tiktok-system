import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { ApiError, getMe, getNotifications, login, logout, refreshSession, register } from './api';
import { Feed } from './Feed';
import {
  CloseIcon,
  HomeIcon,
  InboxIcon,
  PlayIcon,
  PlusIcon,
  SearchIcon,
  UploadCloudIcon,
  UserIcon,
} from './icons';
import { Notifications } from './Notifications';
import { SearchPanel } from './SearchPanel';
import { Avatar, handleFor } from './ui';
import { Upload } from './Upload';

type Panel = 'upload' | 'search' | 'notifications' | 'account' | null;

const PANEL_TITLE: Record<Exclude<Panel, null>, string> = {
  upload: 'Upload video',
  search: 'Search',
  notifications: 'Inbox',
  account: 'Account',
};

export function App() {
  const [panel, setPanel] = useState<Panel>(null);
  const queryClient = useQueryClient();

  // The session cookie survives a page refresh even though React state
  // doesn't -- check it once on load instead of always showing "Not signed
  // in" right after a reload of an otherwise still-valid session.
  const me = useQuery({ queryKey: ['me'], queryFn: getMe, retry: false });
  const signedIn = !!me.data;

  // Just for the inbox badge; the Notifications panel itself shares this same
  // query key, so this is a second subscriber on one cache entry rather than a
  // second network round trip.
  const notifications = useQuery({
    queryKey: ['notifications'],
    queryFn: getNotifications,
    refetchInterval: 10_000,
    enabled: signedIn,
  });
  const unreadCount = (notifications.data ?? []).filter((n) => !n.read).length;

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

  // Escape closes whatever overlay is open, the same as clicking the scrim.
  useEffect(() => {
    if (!panel) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setPanel(null);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [panel]);

  if (me.isPending) {
    return (
      <div className="splash">
        <BrandMark size="lg" />
      </div>
    );
  }

  if (!signedIn) {
    return <AuthScreen />;
  }

  const displayName = me.data.displayName;
  const accountId = me.data.accountId;

  return (
    <div className="app">
      <nav className="sidebar" aria-label="Primary">
        <div className="sidebar-brand">
          <div className="brand">
            <BrandMark />
            <span className="brand-text">Short</span>
          </div>
        </div>

        <button className="nav-item active" aria-current="page">
          <HomeIcon active />
          <span className="nav-label">For You</span>
        </button>
        <button className="nav-item" onClick={() => setPanel('search')}>
          <SearchIcon />
          <span className="nav-label">Search</span>
        </button>
        <button className="nav-item" onClick={() => setPanel('notifications')}>
          <InboxIcon active={panel === 'notifications'} />
          <span className="nav-label">Inbox</span>
          {unreadCount > 0 && <span className="nav-badge">{unreadCount > 99 ? '99+' : unreadCount}</span>}
        </button>
        <button className="nav-item" onClick={() => setPanel('account')}>
          <UserIcon active={panel === 'account'} />
          <span className="nav-label">Profile</span>
        </button>

        <div className="sidebar-cta">
          <button className="btn-primary btn-block btn-row" onClick={() => setPanel('upload')}>
            <UploadCloudIcon size={18} />
            <span className="cta-label">Upload</span>
          </button>
        </div>

        <div className="sidebar-account">
          <button className="account-row" onClick={() => setPanel('account')}>
            <Avatar seed={accountId} label={displayName} size="sm" />
            <span className="account-meta">
              <span className="account-name">{displayName}</span>
              <span className="account-sub">{handleFor(accountId)}</span>
            </span>
          </button>
        </div>
      </nav>

      <main className="stage">
        <Feed />
      </main>

      <nav className="tabbar" aria-label="Primary">
        <button className="tab-item active" aria-current="page">
          <HomeIcon active />
          Home
        </button>
        <button className="tab-item" onClick={() => setPanel('search')}>
          <SearchIcon />
          Search
        </button>
        <button className="tab-item" onClick={() => setPanel('upload')} aria-label="Upload video">
          <span className="tab-create">
            <PlusIcon size={18} />
          </span>
        </button>
        <button className="tab-item" onClick={() => setPanel('notifications')}>
          <InboxIcon active={panel === 'notifications'} />
          Inbox
          {unreadCount > 0 && <span className="tab-badge">{unreadCount > 9 ? '9+' : unreadCount}</span>}
        </button>
        <button className="tab-item" onClick={() => setPanel('account')}>
          <UserIcon active={panel === 'account'} />
          Profile
        </button>
      </nav>

      {panel && (
        <Sheet title={PANEL_TITLE[panel]} onClose={() => setPanel(null)}>
          {panel === 'upload' && <Upload />}
          {panel === 'search' && <SearchPanel />}
          {panel === 'notifications' && <Notifications />}
          {panel === 'account' && <AccountPanel onDone={() => setPanel(null)} />}
        </Sheet>
      )}
    </div>
  );
}

export function BrandMark({ size }: { size?: 'lg' }) {
  return (
    <span className={`brand-mark${size === 'lg' ? ' brand-mark-lg' : ''}`}>
      <PlayIcon />
    </span>
  );
}

export function Sheet({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <div className="sheet-head">
          <h2>{title}</h2>
          <button className="icon-btn" onClick={onClose} aria-label="Close">
            <CloseIcon size={20} />
          </button>
        </div>
        <div className="sheet-body">{children}</div>
      </div>
    </div>
  );
}

function AccountPanel({ onDone }: { onDone: () => void }) {
  const queryClient = useQueryClient();
  const me = useQuery({ queryKey: ['me'], queryFn: getMe, retry: false });
  const [error, setError] = useState<string | null>(null);

  if (!me.data) return null;

  return (
    <div>
      <div className="btn-row" style={{ gap: '0.85rem', marginBottom: '1.25rem' }}>
        <Avatar seed={me.data.accountId} label={me.data.displayName} size="lg" />
        <div>
          <div style={{ fontWeight: 700, fontSize: '1.05rem' }}>{me.data.displayName}</div>
          <div className="search-hit-sub">{handleFor(me.data.accountId)}</div>
        </div>
      </div>

      <div className="btn-row" style={{ marginBottom: '1.25rem' }}>
        <span className={`badge ${me.data.state === 'ACTIVE' ? 'badge-success' : 'badge-warning'}`}>
          {me.data.state}
        </span>
        {me.data.roles.map((role) => (
          <span key={role} className="badge badge-neutral">
            {role}
          </span>
        ))}
      </div>

      <div className="mono" style={{ marginBottom: '1.25rem' }}>
        {me.data.accountId}
      </div>

      <button
        className="btn-danger-ghost btn-block"
        onClick={async () => {
          try {
            await logout();
            // Logout revokes the token server-side, so anything cached for the
            // old session is now unreachable as well as stale.
            queryClient.clear();
            onDone();
          } catch (e) {
            setError((e as Error).message);
          }
        }}
      >
        Log out
      </button>

      {error && <div className="status-line is-error">{error}</div>}
    </div>
  );
}

function AuthScreen() {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('creator@example.com');
  const [password, setPassword] = useState('correct-horse-battery');
  const [displayName, setDisplayName] = useState('Local Creator');
  const [status, setStatus] = useState<{ text: string; error: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const queryClient = useQueryClient();

  const registering = mode === 'register';
  const canSubmit = email.trim() && password.length >= 1 && (!registering || displayName.trim());

  async function submit() {
    setBusy(true);
    try {
      if (registering) {
        await register(email.trim(), password, displayName.trim());
        setMode('login');
        setStatus({ text: 'Account created. Sign in to continue.', error: false });
      } else {
        await login(email.trim(), password);
        setStatus(null);
        await queryClient.invalidateQueries({ queryKey: ['me'] });
      }
    } catch (e) {
      setStatus({ text: (e as Error).message, error: true });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth">
      <div className="auth-card">
        <div className="brand">
          <BrandMark size="lg" />
          <span>Short</span>
        </div>

        <h1 className="auth-title">{registering ? 'Create an account' : 'Log in to Short'}</h1>
        <p className="auth-sub">
          {registering ? 'Pick a name people will see on your videos.' : 'Watch, upload and react to short videos.'}
        </p>

        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (canSubmit && !busy) void submit();
          }}
        >
          {registering && (
            <label className="field">
              <span className="field-label">Display name</span>
              <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} autoComplete="nickname" />
            </label>
          )}

          <label className="field">
            <span className="field-label">Email</span>
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
          </label>

          <label className="field">
            <span className="field-label">Password</span>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete={registering ? 'new-password' : 'current-password'}
            />
            <span className="field-hint">At least 12 characters.</span>
          </label>

          <button
            type="submit"
            className="btn-primary btn-block"
            style={{ marginTop: '1.35rem' }}
            disabled={!canSubmit || busy}
          >
            {busy ? 'Working…' : registering ? 'Sign up' : 'Log in'}
          </button>
        </form>

        {status && <div className={`status-line${status.error ? ' is-error' : ''}`}>{status.text}</div>}

        <p className="auth-switch">
          {registering ? 'Already have an account?' : "Don't have an account?"}{' '}
          <button
            type="button"
            onClick={() => {
              setMode(registering ? 'login' : 'register');
              setStatus(null);
            }}
          >
            {registering ? 'Log in' : 'Sign up'}
          </button>
        </p>
      </div>
    </div>
  );
}
