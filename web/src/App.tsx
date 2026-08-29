import { useState } from 'react';
import { login, logout, probeMedia, register } from './api';
import { Feed } from './Feed';
import { Notifications } from './Notifications';
import { SearchPanel } from './SearchPanel';
import { Upload } from './Upload';

const SAMPLE_VIDEO_ID = '3f2504e0-4f89-41d3-9a0c-0305e82c3301';

export function App() {
  const [email, setEmail] = useState('creator@example.com');
  const [password, setPassword] = useState('correct-horse-battery');
  const [status, setStatus] = useState<string>('Not signed in.');
  const [mediaStatus, setMediaStatus] = useState<string>('');
  const [signedIn, setSignedIn] = useState(false);

  async function run(label: string, action: () => Promise<string>) {
    try {
      setStatus(`${label}...`);
      setStatus(await action());
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
                const session = await login(email, password);
                setSignedIn(true);
                return `Signed in as ${session.accountId}. Session cookie set (HttpOnly, so JS cannot read it).`;
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
                setSignedIn(false);
                return 'Signed out; session cookie cleared.';
              })
            }
          >
            Log out
          </button>
        </div>

        <div className="log-panel">
          {status}
          {mediaStatus ? `\n${mediaStatus}` : ''}
        </div>
      </section>

      <section className="card">
        <div className="card-head">
          <h2>Media gateway probe</h2>
        </div>
        <p className="card-desc">
          Sends a request with no Authorization header, exactly like hls.js. Expect <code>401</code> without a
          playback cookie for this unknown sample video — real playback comes from Upload below, which requests a
          real preview session first.
        </p>
        <button
          className="btn-sm"
          onClick={async () => {
            const code = await probeMedia(SAMPLE_VIDEO_ID);
            setMediaStatus(`GET /media/videos/${SAMPLE_VIDEO_ID}/1/master.m3u8 responded ${code}`);
          }}
        >
          Probe /media
        </button>
      </section>

      <Upload />
      <Feed />
      <SearchPanel />
      <Notifications />
    </div>
  );
}
