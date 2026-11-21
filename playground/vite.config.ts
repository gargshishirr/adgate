import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

/** The playground's header states how many golden vectors the port is verified against; count them at build time. */
function goldenVectorCount(): number {
  const golden = JSON.parse(readFileSync(fileURLToPath(new URL('./src/engine/golden.json', import.meta.url)), 'utf8')) as Record<
    string,
    unknown
  >;
  return Object.values(golden).reduce<number>((total, section) => total + (Array.isArray(section) ? section.length : 0), 0);
}

export default defineConfig({
  base: './',
  define: {
    __GOLDEN_VECTOR_COUNT__: JSON.stringify(goldenVectorCount()),
  },
  esbuild: {
    jsx: 'automatic',
    jsxImportSource: 'preact',
  },
  build: {
    target: 'es2022',
    sourcemap: false,
  },
  test: {
    include: ['src/**/*.test.ts'],
    environment: 'node',
  },
});
