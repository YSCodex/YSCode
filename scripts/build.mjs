import * as esbuild from 'esbuild';
import { readFileSync, writeFileSync, mkdirSync, existsSync, cpSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..');

const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf-8'));

const external = [];

async function build() {
  console.log('Building YS Code Agent...');

  const entryPoints = [
    join(root, 'src/cli/index.ts'),
  ];

  const result = await esbuild.build({
    entryPoints,
    outfile: join(root, 'dist/cli/index.cjs'),
    bundle: true,
    platform: 'node',
    target: 'node26',
    format: 'cjs',
    sourcemap: true,
    minify: false,
    external,
    loader: {
      '.node': 'copy',
    },
    define: {
      __APP_VERSION__: `"${pkg.version}"`,
    },
    banner: {
      js: '#!/usr/bin/env node\n',
    },
  });

  if (result.errors.length > 0) {
    console.error('Build failed:');
    result.errors.forEach(e => console.error(e));
    process.exit(1);
  }

  console.log('Build completed successfully!');
}

build().catch(err => {
  console.error(err);
  process.exit(1);
});
