import fs from 'fs/promises';
import path from 'path';

const CACHE_DIR = './data_cache';

async function fileExists(filePath) {
  try {
    await fs.access(filePath);
    return true;
  } catch {
    return false;
  }
}

export async function run() {
  console.log('--- Step 2: Parsing Wordbooks ---');
  const books = ['CET4_T.json', 'CET6_T.json', 'IELTS_3_T.json', 'TOEFL_3_T.json'];
  const parsedWords = {};

  for (const book of books) {
    const bookPath = path.join(CACHE_DIR, book);
    const bookName = book.split('_')[0].toLowerCase(); // cet4, cet6, ielts, toefl

    if (!await fileExists(bookPath)) {
      console.warn(`⚠️ Book cache file ${book} does not exist, skipping.`);
      continue;
    }

    try {
      const data = JSON.parse(await fs.readFile(bookPath, 'utf-8'));
      console.log(`Parsing ${book} (${data.length} items)...`);

      for (const entry of data) {
        const rawWord = entry.word?.trim();
        if (!rawWord) continue;

        const word = rawWord.toLowerCase();
        const phonetic = entry.phonetic || '';
        const trans = Array.isArray(entry.trans) ? entry.trans.join('; ') : (entry.trans || '');

        if (!parsedWords[word]) {
          parsedWords[word] = {
            word: rawWord,
            phonetic: phonetic,
            translations: [],
            books: new Set()
          };
        }

        if (trans) {
          parsedWords[word].translations.push(trans);
        }
        parsedWords[word].books.add(bookName);
      }
    } catch (e) {
      console.error(`Error parsing ${book}:`, e);
    }
  }

  // Save parsed mid-state
  const parsedList = Object.values(parsedWords).map(w => ({
    word: w.word,
    phonetic: w.phonetic,
    translations: [...new Set(w.translations)],
    books: [...w.books]
  }));

  const outputPath = path.join(CACHE_DIR, 'parsed_raw_words.json');
  await fs.writeFile(outputPath, JSON.stringify(parsedList, null, 2), 'utf-8');
  console.log(`Parsed ${parsedList.length} unique words and saved to ${outputPath}.`);
  return parsedList;
}

if (import.meta.url.endsWith(process.argv[1] || '')) {
  run().catch(console.error);
}
