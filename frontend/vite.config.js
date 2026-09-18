import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  base: '/console/',
  build: {
    outDir: '../load-balancer/src/main/resources/static/console',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/admin': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
