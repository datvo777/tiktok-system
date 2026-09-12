import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import {
  ApiError,
  changeHandle,
  changePassword,
  checkHandle,
  getMe,
  getNotifications,
  login,
  logout,
  refreshSession,
  register,
  updateProfile,
} from './api';
import { CreatorProfile } from './CreatorProfile';
import { Feed, VideoView } from './Feed';
import { Favorites } from './Favorites';
import {
  BookmarkIcon,
  ChevronRightIcon,
  CloseIcon,
  GridIcon,
  HomeIcon,
  InboxIcon,
  KeyIcon,
  PencilIcon,
  PlayIcon,
  PlusIcon,
  SearchIcon,
  UploadCloudIcon,
  UserIcon,
} from './icons';
import { MyVideos } from './MyVideos';
import { Notifications } from './Notifications';
import { SearchPanel } from './SearchPanel';
import { dismiss, navigate, setSheet, type Sheet as SheetName } from './router';
import { ViewerProvider } from './viewer';
import { Avatar, formatHandle } from './ui';
import { Upload } from './Upload';
import { useRoute } from './useRoute';

const PANEL_TITLE: Record<SheetName, string> = {
  signIn: 'Sign in',
  upload: 'Upload video',
  search: 'Search',
  notifications: 'Inbox',
  account: 'Account',
  myVideos: 'My videos',
  favorites: 'Favorites',
};

export function App() {
  // Navigation lives in the URL rather than in component state, so Back closes
  // a sheet instead of leaving the app, and every screen can be linked to.
  const route = useRoute();
  const panel = route.sheet;
  const queryClient = useQueryClient();

  // The session cookie survives a page refresh even though React state
  // doesn't -- check it once on load instead of always showing "Not signed
  // in" right after a reload of an otherwise still-valid session.
  const me = useQuery({ queryKey: ['me'], queryFn: getMe, retry: false });
  const signedIn = !!me.data;

  // Just for the inbox badge; the Notifications panel itself shares this same
  // query key, so this is a second subscriber on one cache entry rather than a
  // second network round trip.
  // Same query key and shape as the Notifications panel, so this stays a second
  // subscriber on one cache entry rather than a second poll. The unread total is
  // computed server-side across the whole account, so the badge is right even
  // when the unread notifications are older than the first page.
  const notifications = useInfiniteQuery({
    queryKey: ['notifications'],
    queryFn: ({ pageParam }: { pageParam: string | null }) => getNotifications(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    refetchInterval: 10_000,
    enabled: signedIn,
  });
  const unreadCount = notifications.data?.pages[0]?.unreadCount ?? 0;

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

  const overlayOpen = panel !== null || route.view.kind === 'creator';

  // Escape closes whatever overlay is open, the same as clicking the scrim --
  // and, like the Back button, by unwinding the history entry that opened it.
  useEffect(() => {
    if (!overlayOpen) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && dismiss();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [overlayOpen]);

  if (me.isPending) {
    return (
      <div className="splash">
        <BrandMark size="lg" />
      </div>
    );
  }

  const displayName = me.data?.displayName ?? null;
  const accountId = me.data?.accountId ?? null;

  // "Home" is only the current page when nothing is open over it.
  const atFeed = route.view.kind === 'feed' && panel === null;
  const navClass = (sheet: SheetName) => `nav-item${panel === sheet ? ' active' : ''}`;
  const tabClass = (sheet: SheetName) => `tab-item${panel === sheet ? ' active' : ''}`;

  // Signed-out viewers see the whole app; the actions that write ask them to
  // sign in at the moment they try, rather than at the door.
  const viewerContext = {
    viewerId: accountId,
    promptSignIn: () => setSheet('signIn'),
  };

  const guarded = (sheet: SheetName) => () => setSheet(accountId ? sheet : 'signIn');

  return (
    <ViewerProvider value={viewerContext}>
    <div className="app">
      <nav className="sidebar" aria-label="Primary">
        <div className="sidebar-brand">
          <div className="brand">
            <BrandMark />
            <span className="brand-text">Short</span>
          </div>
        </div>

        {/* Reflects the route rather than being hard-coded active: the nav used
            to claim "For You" was the current page even while a sheet or a
            profile was open over it. */}
        <button
          className={`nav-item${atFeed ? ' active' : ''}`}
          aria-current={atFeed ? 'page' : undefined}
          onClick={() => navigate('/')}
        >
          <HomeIcon active={atFeed} />
          <span className="nav-label">For You</span>
        </button>
        <button className={navClass('search')} onClick={() => setSheet('search')}>
          <SearchIcon />
          <span className="nav-label">Search</span>
        </button>
        <button className={navClass('notifications')} onClick={guarded('notifications')}>
          <InboxIcon active={panel === 'notifications'} />
          <span className="nav-label">Inbox</span>
          {unreadCount > 0 && <span className="nav-badge">{unreadCount > 99 ? '99+' : unreadCount}</span>}
        </button>
        <button className={navClass('favorites')} onClick={guarded('favorites')}>
          <BookmarkIcon filled={panel === 'favorites'} />
          <span className="nav-label">Favorites</span>
        </button>
        <button className={navClass('myVideos')} onClick={guarded('myVideos')}>
          <GridIcon active={panel === 'myVideos'} />
          <span className="nav-label">My videos</span>
        </button>
        <button className={navClass('account')} onClick={guarded('account')}>
          <UserIcon active={panel === 'account'} />
          <span className="nav-label">Profile</span>
        </button>

        <div className="sidebar-cta">
          <button className="btn-primary btn-block btn-row" onClick={guarded('upload')}>
            <UploadCloudIcon size={18} />
            <span className="cta-label">Upload</span>
          </button>
        </div>

        <div className="sidebar-account">
          {accountId && me.data ? (
            <button className="account-row" onClick={guarded('account')}>
              <Avatar seed={accountId} label={displayName ?? undefined} size="sm" />
              <span className="account-meta">
                <span className="account-name">{displayName}</span>
                <span className="account-sub">{formatHandle(me.data.handle)}</span>
              </span>
            </button>
          ) : (
            <button className="btn-primary btn-block" onClick={() => setSheet('signIn')}>
              Sign in
            </button>
          )}
        </div>
      </nav>

      <main className="stage">
        {/* A creator profile opens over the feed rather than replacing it, so
            dismissing it returns the viewer to their scroll position. */}
        {route.view.kind === 'video' ? (
          <VideoView videoId={route.view.videoId} />
        ) : (
          <Feed />
        )}
      </main>

      <nav className="tabbar" aria-label="Primary">
        <button
          className={`tab-item${atFeed ? ' active' : ''}`}
          aria-current={atFeed ? 'page' : undefined}
          onClick={() => navigate('/')}
        >
          <HomeIcon active={atFeed} />
          Home
        </button>
        <button className={tabClass('search')} onClick={() => setSheet('search')}>
          <SearchIcon />
          Search
        </button>
        <button className="tab-item" onClick={guarded('upload')} aria-label="Upload video">
          <span className="tab-create">
            <PlusIcon size={18} />
          </span>
        </button>
        <button className={tabClass('notifications')} onClick={guarded('notifications')}>
          <InboxIcon active={panel === 'notifications'} />
          Inbox
          {unreadCount > 0 && <span className="tab-badge">{unreadCount > 9 ? '9+' : unreadCount}</span>}
        </button>
        <button className={tabClass('myVideos')} onClick={guarded('myVideos')}>
          <GridIcon active={panel === 'myVideos'} />
          My videos
        </button>
        <button className={tabClass('account')} onClick={guarded('account')}>
          <UserIcon active={panel === 'account'} />
          Profile
        </button>
      </nav>

      {/* Its own route, so a profile opened from a comment can be linked to and
          Back closes it. */}
      {route.view.kind === 'creator' && (
        <Sheet title="Profile" onClose={() => dismiss()}>
          <CreatorProfile creatorId={route.view.creatorId} />
        </Sheet>
      )}

      {panel && (
        <Sheet
          title={PANEL_TITLE[panel]}
          onClose={() => dismiss()}
          large={panel === 'search' || panel === 'favorites'}
        >
          {panel === 'signIn' && <AuthPanel onDone={() => dismiss()} />}
          {panel === 'upload' && <Upload onDone={() => dismiss()} />}
          {panel === 'search' && <SearchPanel />}
          {panel === 'notifications' && <Notifications />}
          {panel === 'account' && (
            <AccountPanel onDone={() => dismiss()} onOpenFavorites={() => setSheet('favorites')} />
          )}
          {panel === 'myVideos' && <MyVideos />}
          {panel === 'favorites' && <Favorites />}
        </Sheet>
      )}
    </div>
    </ViewerProvider>
  );
}

export function BrandMark({ size }: { size?: 'lg' }) {
  return (
    <span className={`brand-mark${size === 'lg' ? ' brand-mark-lg' : ''}`}>
      <PlayIcon />
    </span>
  );
}

/**
 * Every element a keyboard can reach, in document order. `:not([disabled])`
 * matters because a disabled control is focusable in neither direction and
 * including it would let Tab appear to do nothing.
 */
const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function Sheet({
  title,
  onClose,
  children,
  large,
}: {
  title: string;
  onClose: () => void;
  children: React.ReactNode;
  /**
   * Search needs more room than a form-sized sheet, and a *fixed* amount of it:
   * sized to its content, the dialog resized on every keystroke as the result
   * count changed, which moves the rows out from under the pointer mid-scan.
   */
  large?: boolean | undefined;
}) {
  const dialogRef = useRef<HTMLDivElement>(null);

  /**
   * A modal dialog has to keep focus inside it and give it back on close.
   * Without this, Tab walked straight out of the sheet into the feed behind it —
   * which is still on screen and still interactive to a screen reader — and
   * dismissing the sheet dropped focus to the top of the document, so a keyboard
   * user had to tab all the way back to where they were.
   */
  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const dialog = dialogRef.current;

    // Focus the first real control, or the dialog itself when it holds none.
    const first = dialog?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? dialog)?.focus();

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Tab' || !dialog) return;
      const focusable = [...dialog.querySelectorAll<HTMLElement>(FOCUSABLE)].filter(
        // offsetParent is null for anything display:none, which would otherwise
        // be a stop on the tab cycle that the eye cannot find.
        (el) => el.offsetParent !== null || el === document.activeElement,
      );
      if (focusable.length === 0) return;

      const firstEl = focusable[0] as HTMLElement;
      const lastEl = focusable[focusable.length - 1] as HTMLElement;
      if (e.shiftKey && document.activeElement === firstEl) {
        e.preventDefault();
        lastEl.focus();
      } else if (!e.shiftKey && document.activeElement === lastEl) {
        e.preventDefault();
        firstEl.focus();
      }
    };

    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      // Only if it is still in the document: the element that opened the sheet
      // may itself have been unmounted by whatever the sheet did.
      if (previouslyFocused?.isConnected) previouslyFocused.focus();
    };
  }, []);

  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div
        ref={dialogRef}
        className={`sheet${large ? ' sheet-large' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        onClick={(e) => e.stopPropagation()}
      >
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

function AccountPanel({ onDone, onOpenFavorites }: { onDone: () => void; onOpenFavorites: () => void }) {
  const queryClient = useQueryClient();
  const me = useQuery({ queryKey: ['me'], queryFn: getMe, retry: false });
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [changingPassword, setChangingPassword] = useState(false);

  if (!me.data) return null;

  return (
    <div>
      <div className="btn-row" style={{ gap: '0.85rem', marginBottom: '1.25rem' }}>
        <Avatar seed={me.data.accountId} label={me.data.displayName} size="lg" />
        <div>
          <div style={{ fontWeight: 700, fontSize: '1.05rem' }}>{me.data.displayName}</div>
          <div className="search-hit-sub">{formatHandle(me.data.handle)}</div>
        </div>
      </div>

      {me.data.bio && <p style={{ marginBottom: '1.25rem' }}>{me.data.bio}</p>}

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

      {editing ? (
        <EditProfileForm
          initialDisplayName={me.data.displayName}
          initialHandle={me.data.handle}
          initialBio={me.data.bio ?? ''}
          onClose={() => setEditing(false)}
        />
      ) : (
        <button className="account-link" onClick={() => setEditing(true)}>
          <PencilIcon size={18} />
          <span>Edit profile</span>
          <ChevronRightIcon size={16} className="account-link-cue" />
        </button>
      )}

      {changingPassword ? (
        <ChangePasswordForm onClose={() => setChangingPassword(false)} />
      ) : (
        <button className="account-link" onClick={() => setChangingPassword(true)}>
          <KeyIcon size={18} />
          <span>Change password</span>
          <ChevronRightIcon size={16} className="account-link-cue" />
        </button>
      )}

      {/* The sidebar is hidden below 767px, so this is the only route to the
          favorites panel on a phone. */}
      <button className="account-link" onClick={onOpenFavorites}>
        <BookmarkIcon size={18} />
        <span>Favorites</span>
        <ChevronRightIcon size={16} className="account-link-cue" />
      </button>

      <button
        className="btn-danger-ghost btn-block"
        style={{ marginTop: '1rem' }}
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

/**
 * Display name and bio.
 *
 * <p>The account module previously exposed no update of any kind, so the name
 * chosen at registration was permanent — a typo in it could not be corrected.
 */
function EditProfileForm({
  initialDisplayName,
  initialHandle,
  initialBio,
  onClose,
}: {
  initialDisplayName: string;
  initialHandle: string;
  initialBio: string;
  onClose: () => void;
}) {
  const [displayName, setDisplayName] = useState(initialDisplayName);
  const [handle, setHandle] = useState(initialHandle);
  const [bio, setBio] = useState(initialBio);
  const queryClient = useQueryClient();

  const trimmedHandle = handle.trim().replace(/^@/, '');
  const handleChanged = trimmedHandle.toLowerCase() !== initialHandle.toLowerCase();

  // Debounced so a fast typist costs one request rather than one per keystroke,
  // and only asked at all once the value differs from what they already have.
  const [checkTerm, setCheckTerm] = useState('');
  useEffect(() => {
    if (!handleChanged || trimmedHandle.length === 0) {
      setCheckTerm('');
      return;
    }
    const timer = setTimeout(() => setCheckTerm(trimmedHandle), 350);
    return () => clearTimeout(timer);
  }, [trimmedHandle, handleChanged]);

  const availability = useQuery({
    queryKey: ['handle-available', checkTerm],
    queryFn: () => checkHandle(checkTerm),
    enabled: checkTerm.length > 0,
    retry: false,
  });

  const save = useMutation({
    mutationFn: async () => {
      // Two calls because they are two different acts server-side, and the
      // username has its own failure mode. Handle first: if it is taken, the
      // profile edit should not have already been applied.
      if (handleChanged) await changeHandle(trimmedHandle);
      await updateProfile({ displayName: displayName.trim(), bio: bio.trim() });
    },
    onSuccess: async () => {
      // Name and handle are denormalised into comments, search hits and creator
      // profiles, so everything holding a copy of them is now stale.
      await queryClient.invalidateQueries();
      onClose();
    },
  });

  const dirty = displayName.trim() !== initialDisplayName || bio.trim() !== initialBio || handleChanged;
  const handleBlocked = handleChanged && availability.data?.available === false;

  return (
    <div className="callout" style={{ marginBottom: '0.75rem' }}>
      <label className="field">
        <span className="field-label">Display name</span>
        <input value={displayName} maxLength={100} onChange={(e) => setDisplayName(e.target.value)} />
      </label>
      <label className="field">
        <span className="field-label">Username</span>
        <div className="handle-field">
          <span className="handle-sigil" aria-hidden="true">
            @
          </span>
          <input
            value={trimmedHandle}
            maxLength={30}
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            aria-invalid={handleBlocked || undefined}
            onChange={(e) => setHandle(e.target.value)}
          />
        </div>
        <span className={`field-hint${handleBlocked ? ' is-error' : ''}`}>
          {!handleChanged
            ? 'How people find and mention you.'
            : availability.isFetching || checkTerm !== trimmedHandle
              ? 'Checking…'
              : (availability.data?.reason ?? `@${trimmedHandle} is available.`)}
        </span>
      </label>
      <label className="field">
        <span className="field-label">Bio</span>
        <textarea
          value={bio}
          maxLength={300}
          rows={3}
          placeholder="Tell people what you post."
          onChange={(e) => setBio(e.target.value)}
        />
        <span className="field-hint">{300 - bio.length} characters left.</span>
      </label>
      <div className="btn-row">
        <button
          className="btn-primary btn-sm"
          disabled={!displayName.trim() || !dirty || handleBlocked || save.isPending}
          onClick={() => save.mutate()}
        >
          {save.isPending ? 'Saving…' : 'Save'}
        </button>
        <button className="btn-ghost btn-sm" onClick={onClose}>
          Cancel
        </button>
      </div>
      {save.isError && <div className="status-line is-error">{(save.error as Error).message}</div>}
    </div>
  );
}

function ChangePasswordForm({ onClose }: { onClose: () => void }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [done, setDone] = useState(false);

  const change = useMutation({
    mutationFn: () => changePassword(current, next),
    onSuccess: () => {
      setCurrent('');
      setNext('');
      setDone(true);
    },
  });

  const tooShort = next.length > 0 && next.length < PASSWORD_MIN;

  return (
    <div className="callout" style={{ marginBottom: '0.75rem' }}>
      <label className="field">
        <span className="field-label">Current password</span>
        <input
          type="password"
          autoComplete="current-password"
          value={current}
          maxLength={PASSWORD_MAX}
          onChange={(e) => setCurrent(e.target.value)}
        />
      </label>
      <label className="field">
        <span className="field-label">New password</span>
        <input
          type="password"
          autoComplete="new-password"
          value={next}
          minLength={PASSWORD_MIN}
          maxLength={PASSWORD_MAX}
          onChange={(e) => setNext(e.target.value)}
        />
        <span className={`field-hint${tooShort ? ' is-error' : ''}`}>
          At least {PASSWORD_MIN} characters.
        </span>
      </label>
      <div className="btn-row">
        <button
          className="btn-primary btn-sm"
          disabled={!current || next.length < PASSWORD_MIN || change.isPending}
          onClick={() => change.mutate()}
        >
          {change.isPending ? 'Saving…' : 'Change password'}
        </button>
        <button className="btn-ghost btn-sm" onClick={onClose}>
          {done ? 'Close' : 'Cancel'}
        </button>
      </div>
      {done && <div className="status-line">Password changed.</div>}
      {change.isError && <div className="status-line is-error">{(change.error as Error).message}</div>}
    </div>
  );
}

/**
 * Mirrors `@Size(min = 12, max = 200)` on the server's registration DTO. Kept as
 * named constants because the hint text and the submit guard have to agree —
 * they previously did not, so the form promised "at least 12 characters" while
 * accepting one and letting the server reject it.
 */
const PASSWORD_MIN = 12;
const PASSWORD_MAX = 200;

/**
 * Only ever prefilled during `vite dev`. A production bundle ships empty fields:
 * a real-looking credential pair sitting in the login form is a footgun the
 * moment this is deployed anywhere, and it reads as a demo rather than a product.
 */
const DEV_PREFILL = import.meta.env.DEV
  ? { email: 'creator@example.com', password: 'correct-horse-battery', displayName: 'Local Creator' }
  : { email: '', password: '', displayName: '' };

/**
 * The sign-in form, as a sheet rather than a wall.
 *
 * <p>It used to be the entire app for anyone without a session: the feed, search
 * and every shared link ended here. Now the app is browsable signed out and this
 * opens only when someone tries to do something that needs an account, or asks
 * for it.
 */
function AuthPanel({ onDone }: { onDone: () => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState(DEV_PREFILL.email);
  const [password, setPassword] = useState(DEV_PREFILL.password);
  const [displayName, setDisplayName] = useState(DEV_PREFILL.displayName);
  const [status, setStatus] = useState<{ text: string; error: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const queryClient = useQueryClient();

  const registering = mode === 'register';
  // Sign-in deliberately does not apply the minimum: an account created before
  // the rule tightened still has to be able to log in.
  const passwordOk = registering
    ? password.length >= PASSWORD_MIN && password.length <= PASSWORD_MAX
    : password.length >= 1;
  const canSubmit = email.trim() && passwordOk && (!registering || displayName.trim());
  const passwordShort = registering && password.length > 0 && password.length < PASSWORD_MIN;

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
        // Everything cached while signed out was computed for a stranger: the
        // feed ranking, `liked`, `following`, the empty inbox.
        await queryClient.invalidateQueries();
        onDone();
      }
    } catch (e) {
      setStatus({ text: (e as Error).message, error: true });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-panel">
      <div>
        <h3 className="auth-title">{registering ? 'Create an account' : 'Log in to Short'}</h3>
        <p className="auth-sub">
          {registering
            ? 'Pick a name people will see on your videos.'
            : 'You need an account to like, comment, follow and post.'}
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
              minLength={registering ? PASSWORD_MIN : undefined}
              maxLength={PASSWORD_MAX}
              aria-describedby={registering ? 'password-hint' : undefined}
              aria-invalid={passwordShort || undefined}
            />
            {registering && (
              <span id="password-hint" className={`field-hint${passwordShort ? ' is-error' : ''}`}>
                {passwordShort
                  ? `${PASSWORD_MIN - password.length} more character${
                      PASSWORD_MIN - password.length === 1 ? '' : 's'
                    } needed.`
                  : `At least ${PASSWORD_MIN} characters.`}
              </span>
            )}
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
