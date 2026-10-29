import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const source = path.join(root, 'dist');
const target = path.join(root, 'android', 'app', 'src', 'main', 'assets', 'web');

if (!fs.existsSync(source)) {
  throw new Error('dist/ 不存在，请先运行 npm run build');
}

fs.rmSync(target, { recursive: true, force: true });
fs.mkdirSync(target, { recursive: true });
fs.cpSync(source, target, { recursive: true });
console.log(`Android assets synced: ${path.relative(root, target)}`);
