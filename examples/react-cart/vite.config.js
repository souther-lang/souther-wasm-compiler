import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  // The compiled model is served as it is. Nothing rewrites it and nothing bundles it: what the
  // browser loads is the file the compiler wrote.
  assetsInclude: ["**/*.wasm"],
});
