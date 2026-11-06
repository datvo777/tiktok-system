import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Same single-origin arrangement as web/ (brief section 12.1): the admin
// portal gets its own port and its own proxy so its SameSite=Lax session
// cookie rides along on /internal calls without permissive CORS.
//
// Cookies are kept per host, not per port, and the session cookie has no Domain: signing in to
// http://localhost:5174 would replace the session of the user web app on http://localhost:5173 in the
// same browser (and the reverse). Open the console at http://admin.localhost:5174 instead; *.localhost
// resolves to this machine and gets its own cookie jar.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
      '/internal': { target: 'http://localhost:8080', changeOrigin: false },
      '/media': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
});
