import fs from 'fs/promises';
import path from 'path';

const CACHE_DIR = './data_cache';
const LOCAL_DICTS_DIR = './app/src/main/assets/dicts';

async function fileExists(filePath) {
  try {
    await fs.access(filePath);
    return true;
  } catch {
    return false;
  }
}

export async function run() {
  console.log('--- Step 3: Merging & Deduplicating Vocabulary ---');
  
  let wordsMap = {};

  // 1. Try to load parsed raw words from cache
  const parsedPath = path.join(CACHE_DIR, 'parsed_raw_words.json');
  if (await fileExists(parsedPath)) {
    try {
      const cached = JSON.parse(await fs.readFile(parsedPath, 'utf-8'));
      for (const item of cached) {
        const key = item.word.toLowerCase();
        wordsMap[key] = {
          word: item.word,
          phonetic: item.phonetic,
          translations: item.translations,
          books: new Set(item.books)
        };
      }
      console.log(`Loaded ${cached.length} parsed words from cache.`);
    } catch (e) {
      console.error('Error reading parsed raw words cache:', e);
    }
  }

  // 2. Read local dict JSON files as fallback and to retrieve extra entries (like Coder, IELTS, etc.)
  try {
    const filenames = ['cet4.json', 'cet6.json', 'ielts.json', 'toefl.json', 'coder.json'];
    for (const filename of filenames) {
      const localPath = path.join(LOCAL_DICTS_DIR, filename);
      if (await fileExists(localPath)) {
        console.log(`Reading local dict file: ${filename}...`);
        const localData = JSON.parse(await fs.readFile(localPath, 'utf-8'));
        const bookName = filename.split('.')[0].toLowerCase(); // cet4, cet6, ielts, toefl, coder

        for (const entry of localData) {
          const rawWord = entry.word?.trim();
          if (!rawWord) continue;

          const key = rawWord.toLowerCase();
          if (!wordsMap[key]) {
            wordsMap[key] = {
              word: rawWord,
              phonetic: entry.phonetic || '',
              translations: [entry.translation],
              books: new Set()
            };
          }
          
          wordsMap[key].books.add(bookName);
          if (entry.translation && !wordsMap[key].translations.includes(entry.translation)) {
            wordsMap[key].translations.push(entry.translation);
          }
        }
      }
    }
  } catch (e) {
    console.error('Error reading local dict files:', e);
  }

  // Normalize and merge translation definitions
  const mergedList = Object.values(wordsMap).map(w => {
    // Join translations into a single clean semicolon-separated definition list
    const combinedTrans = w.translations
      .flatMap(t => t.split(/[;\uff1b,]/))
      .map(t => t.trim())
      .filter(t => t.length > 0);

    const uniqueTrans = [...new Set(combinedTrans)];

    return {
      word: w.word,
      phonetic: w.phonetic,
      translation: uniqueTrans.slice(0, 5).join('；'), // Max 5 clean definitions
      books: [...w.books]
    };
  });

  const mergedPath = path.join(CACHE_DIR, 'merged_vocabulary.json');
  await fs.writeFile(mergedPath, JSON.stringify(mergedList, null, 2), 'utf-8');
  console.log(`Successfully merged ${mergedList.length} unique words to ${mergedPath}.`);
  return mergedList;
}

if (import.meta.url.endsWith(process.argv[1] || '')) {
  run().catch(console.error);
}
