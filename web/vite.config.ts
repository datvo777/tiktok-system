import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Single origin in development (brief section 12.1).
//
// The session cookie is SameSite=Lax, and Lax cookies are not sent on
// cross-origin subresource requests -- which is exactly what an HLS segment
// request is. Proxying /api, /internal and /media through the dev server means
// the browser only ever sees http://localhost:5173, so the cookie rides along.
//
// Do NOT replace this with permissive backend CORS plus credentials. The proxy
// is the supported local setup.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
      '/internal': { target: 'http://localhost:8080', changeOrigin: false },
      '/media': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
});
