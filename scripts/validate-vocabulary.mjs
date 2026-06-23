import fs from 'fs/promises';
import path from 'path';

const CACHE_DIR = './data_cache';

export async function run() {
  console.log('--- Step 5: Validating Vocabulary ---');
  
  const matchedPath = path.join(CACHE_DIR, 'matched_vocabulary.json');
  try {
    const data = JSON.parse(await fs.readFile(matchedPath, 'utf-8'));
    console.log(`Validating ${data.length} entries...`);

    let validCount = 0;
    let invalidCount = 0;
    const validatedData = [];

    for (const entry of data) {
      if (!entry.word || typeof entry.word !== 'string') {
        console.warn(`❌ Word missing or invalid:`, entry);
        invalidCount++;
        continue;
      }

      if (!entry.books || !Array.isArray(entry.books) || entry.books.length === 0) {
        console.warn(`⚠️ Word '${entry.word}' has no books associated, defaulting to high_freq.`);
        entry.books = ['high_freq'];
      }

      // Ensure examples list is sound
      if (!entry.examples || !Array.isArray(entry.examples) || entry.examples.length === 0) {
        console.warn(`❌ Word '${entry.word}' has empty or invalid examples list.`);
        invalidCount++;
        continue;
      }

      // Check cloze leak
      for (const ex of entry.examples) {
        if (ex.clozeEn.toLowerCase().includes(entry.word.toLowerCase())) {
          console.warn(`⚠️ Cloze leak detected for '${entry.word}': "${ex.clozeEn}". Repairing...`);
          // Repair cloze by masking again
          const escaped = entry.word.replace(/[-\/\\^$*+?.()|[\]{}]/g, '\\$&');
          const regex = new RegExp(`\\b${escaped}(s|es|ed|d|ing|ly)?\\b`, 'gi');
          ex.clozeEn = ex.originalEn.replace(regex, '_______');
        }
      }

      validCount++;
      validatedData.push(entry);
    }

    const validatedPath = path.join(CACHE_DIR, 'validated_vocabulary.json');
    await fs.writeFile(validatedPath, JSON.stringify(validatedData, null, 2), 'utf-8');
    console.log(`Validation complete. Valid: ${validCount}, Invalid: ${invalidCount}. Saved to ${validatedPath}.`);
    return validatedData;
  } catch (e) {
    console.error('Error during validation:', e);
    return [];
  }
}

if (import.meta.url.endsWith(process.argv[1] || '')) {
  run().catch(console.error);
}
