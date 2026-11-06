import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { confirmTotpEnrollment, startTotpEnrollment, type TotpEnrollment } from './api';
import { QrCode } from './QrCode';

/** The secret in groups of four, which is how an authenticator app shows it for typing in by hand. */
function grouped(secret: string): string {
  return secret.replace(/(.{4})/g, '$1 ').trim();
}

/**
 * Shown to an admin who is signed in without a second factor while the server requires one. Not enrolled: set
 * one up here. Enrolled but signed in before it was switched on: sign in again, so the code is asked for.
 */
export function MfaGate({ enrolled, onSignOut }: { enrolled: boolean; onSignOut: () => void }) {
  if (enrolled) {
    return (
      <section className="panel">
        <div className="panel-body">
          <p className="section-note">
            This session was started before the second factor was required. Sign out and sign in again; you
            will be asked for the code from your authenticator app.
          </p>
          <div className="btn-row">
            <button className="btn-accent" onClick={onSignOut}>
              Sign out
            </button>
          </div>
        </div>
      </section>
    );
  }
  return <Enrolment onSignOut={onSignOut} />;
}

function Enrolment({ onSignOut }: { onSignOut: () => void }) {
  const queryClient = useQueryClient();
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [enrollment, setEnrollment] = useState<TotpEnrollment | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  const [saved, setSaved] = useState(false);
  const [status, setStatus] = useState('');
  const [busy, setBusy] = useState(false);

  async function start() {
    try {
      setBusy(true);
      setStatus('');
      setEnrollment(await startTotpEnrollment(password));
      setPassword('');
    } catch (error) {
      setStatus((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function confirm() {
    try {
      setBusy(true);
      setStatus('');
      // The server replaces this session with one that carries the second factor. The recovery codes are shown
      // before the app moves on, because they are not available again.
      setRecoveryCodes(await confirmTotpEnrollment(code.trim()));
    } catch (error) {
      setStatus((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function finish() {
    await queryClient.invalidateQueries({ queryKey: ['mfa-status'] });
    await queryClient.invalidateQueries({ queryKey: ['me'] });
  }

  if (recoveryCodes) {
    return (
      <section className="panel">
        <div className="panel-body">
          <p className="section-note">
            The second factor is on. These recovery codes each work once if you lose your authenticator. They
            are shown only now: store them somewhere safe, separate from your password.
          </p>
          <pre className="recovery-codes">{recoveryCodes.join('\n')}</pre>
          <label className="field">
            <span className="field-label">
              <input type="checkbox" checked={saved} onChange={(e) => setSaved(e.target.checked)} /> I have
              saved these codes
            </span>
          </label>
          <div className="btn-row">
            <button className="btn-accent" disabled={!saved} onClick={() => void finish()}>
              Continue
            </button>
          </div>
        </div>
      </section>
    );
  }

  if (!enrollment) {
    return (
      <section className="panel">
        <div className="panel-body">
          <p className="section-note">
            A second factor is required to use the moderation console. You will need an authenticator app (for
            example one that supports time-based codes). Enter your password to begin.
          </p>
          <label className="field">
            <span className="field-label">Password</span>
            <input
              type="password"
              value={password}
              autoComplete="current-password"
              onChange={(e) => setPassword(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !busy && password) void start();
              }}
            />
          </label>
          <div className="btn-row">
            <button className="btn-accent" disabled={busy || !password} onClick={() => void start()}>
              Set up
            </button>
            <button onClick={onSignOut}>Sign out</button>
          </div>
          {status && <p className="error-text">{status}</p>}
        </div>
      </section>
    );
  }

  return (
    <section className="panel">
      <div className="panel-body">
        <p className="section-note">
          Scan this with your authenticator app (add an account, then scan a QR code), then type the six-digit
          code it shows.
        </p>
        <QrCode value={enrollment.otpauthUri} label="QR code for your authenticator app" />
        <p className="section-note">
          Cannot scan? Enter this key by hand instead: <code>{grouped(enrollment.secret)}</code> or{' '}
          <a href={enrollment.otpauthUri}>open it in the app</a> on a phone.
        </p>
        <label className="field">
          <span className="field-label">Code</span>
          <input
            value={code}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            onChange={(e) => setCode(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !busy && code) void confirm();
            }}
          />
        </label>
        <div className="btn-row">
          <button
            className="btn-accent"
            disabled={busy || code.trim().length !== 6}
            onClick={() => void confirm()}
          >
            Turn on
          </button>
          <button onClick={onSignOut}>Sign out</button>
        </div>
        {status && <p className="error-text">{status}</p>}
      </div>
    </section>
  );
}
