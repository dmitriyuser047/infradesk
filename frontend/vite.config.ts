import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        // The framework changes far less often than the application: a separate chunk stays cached
        // across releases.
        manualChunks: {
          framework: ['react', 'react-dom', 'react-router-dom', '@tanstack/react-query'],
        },
      },
    },
  },
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', ws: true, changeOrigin: false },
    },
  },
})
