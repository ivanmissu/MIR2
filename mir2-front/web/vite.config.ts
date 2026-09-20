import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: true,
    strictPort: false,
    proxy: {
      '/ws': {
        target: 'ws://127.0.0.1:8080',
        ws: true,
        rewriteWsOrigin: true
      }
    }
  },
  preview: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: true,
    proxy: {
      '/ws': {
        target: 'ws://127.0.0.1:8080',
        ws: true,
        rewriteWsOrigin: true
      }
    }
  }
});
