import fs from 'fs/promises';
import path from 'path';

const CACHE_DIR = './data_cache';

const SOURCES = {
  'CET4_T.json': 'https://raw.githubusercontent.com/Kaiyiwing/qwerty-learner/master/packages/dicts/resources/CET4_T.json',
  'CET6_T.json': 'https://raw.githubusercontent.com/Kaiyiwing/qwerty-learner/master/packages/dicts/resources/CET6_T.json',
  'IELTS_3_T.json': 'https://raw.githubusercontent.com/Kaiyiwing/qwerty-learner/master/packages/dicts/resources/IELTS_3_T.json',
  'TOEFL_3_T.json': 'https://raw.githubusercontent.com/Kaiyiwing/qwerty-learner/master/packages/dicts/resources/TOEFL_3_T.json',
  'tb_vocabulary.json': 'https://raw.githubusercontent.com/vanying/english-vocabulary/master/tb_vocabulary.json',
  'tb_voc_examples.json': 'https://raw.githubusercontent.com/vanying/english-vocabulary/master/tb_voc_examples.json'
};

async function downloadFile(url, filename) {
  const destPath = path.join(CACHE_DIR, filename);
  try {
    console.log(`Downloading ${url} to ${destPath}...`);
    const response = await fetch(url);
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }
    const text = await response.text();
    await fs.writeFile(destPath, text, 'utf-8');
    console.log(`Successfully downloaded ${filename}`);
    return true;
  } catch (error) {
    console.warn(`⚠️ Failed to download ${filename}: ${error.message}. Will use fallback or local mock data.`);
    return false;
  }
}

export async function run() {
  await fs.mkdir(CACHE_DIR, { recursive: true });
  console.log('--- Step 1: Downloading Wordbooks and Examples ---');
  let successCount = 0;
  for (const [filename, url] of Object.entries(SOURCES)) {
    const success = await downloadFile(url, filename);
    if (success) successCount++;
  }
  console.log(`Downloaded ${successCount}/${Object.keys(SOURCES).length} files.`);
}

// Allow executing directly
if (import.meta.url.endsWith(process.argv[1] || '')) {
  run().catch(console.error);
}
