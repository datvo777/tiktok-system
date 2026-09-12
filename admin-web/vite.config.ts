import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Same single-origin arrangement as web/ (brief section 12.1): the admin
// portal gets its own port and its own proxy so its SameSite=Lax session
// cookie rides along on /internal calls without permissive CORS.
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
