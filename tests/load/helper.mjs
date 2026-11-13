// Runs shell commands on behalf of bench.mjs. It is forked before any connection is opened because a
// Node process holding ~10k sockets can no longer spawn children on macOS (spawnSync fails with EBADF),
// and the benchmark needs `ps`, `netstat`, `docker exec` and the backend start/stop script throughout.
import { exec } from 'node:child_process';

process.on('message', ({ id, cmd }) => {
  exec(cmd, { maxBuffer: 64 * 1024 * 1024, env: process.env }, (error, stdout, stderr) => {
    process.send({ id, out: stdout, err: error ? `${error.message}\n${stderr}` : null });
  });
});
process.send({ id: 0, out: 'ready' });
