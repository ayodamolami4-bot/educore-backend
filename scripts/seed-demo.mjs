import {resolve} from 'node:path';
import {seedDemoSchool} from './demo-school.mjs';

if (process.argv.slice(2).join(' ') !== '--demo') {
  console.error('Usage: node scripts/seed-demo.mjs --demo (fictional DEMO school only)');
  process.exitCode = 1;
} else {
  try {
    const root = resolve(import.meta.dirname, '..');
    process.loadEnvFile(resolve(root, '.env'));
    await seedDemoSchool({folder: resolve(root, '.dev'),
      origin: 'http://127.0.0.1:' + (process.env.GATEWAY_PORT || '8080'),
      operatorOrigin: process.env.ACADEMICS_ADMIN_URL ||
        'http://127.0.0.1:' + (process.env.ACADEMICS_ADMIN_PORT || '8092'),
      operatorKey: process.env.OPERATOR_TO_ACADEMICS_KEY});
    console.log('Demo school populated. Private test logins are in .dev/demo-credentials.json.');
    console.log('Open that file locally. Give each teammate only their own role entry.');
  } catch (error) {
    console.error(error instanceof Error ? error.message : 'Demo setup failed. Preserve the private setup files.');
    process.exitCode = 1;
  }
}
