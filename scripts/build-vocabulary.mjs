import { spawnSync } from 'node:child_process';

// Share source validation with the daily server job.
const result = spawnSync(process.env.PYTHON || 'python', ['scripts/update-vocabulary.py'], { stdio: 'inherit' });
if (result.error) throw result.error;
process.exit(result.status ?? 1);
