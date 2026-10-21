import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

// In development the API is the server started with `./gradlew :server:run`: port 8080, or BOOKSOUND_API.
const env = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env ?? {};

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: "autoUpdate",
      includeAssets: ["icon.svg"],
      manifest: {
        name: "BookSound",
        short_name: "BookSound",
        description: "Audiobooks from your BookSound server",
        theme_color: "#0f1117",
        background_color: "#0f1117",
        display: "standalone",
        icons: [{ src: "icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any maskable" }],
      },
      workbox: {
        // The app shell only: the API, covers and audio always come from the server.
        navigateFallbackDenylist: [/^\/api\//],
        globPatterns: ["**/*.{js,css,html,svg,ttf}"],
      },
    }),
  ],
  server: {
    proxy: { "/api": env.BOOKSOUND_API ?? "http://localhost:8080" },
  },
  build: {
    outDir: "dist",
    chunkSizeWarningLimit: 1500,
  },
});
