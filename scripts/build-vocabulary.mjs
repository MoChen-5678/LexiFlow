import fs from 'fs/promises';
import path from 'path';
import { run as runDownload } from './download-wordbooks.mjs';
import { run as runParse } from './parse-wordbooks.mjs';
import { run as runMerge } from './merge-vocabulary.mjs';
import { run as runMatch } from './match-examples.mjs';
import { run as runValidate } from './validate-vocabulary.mjs';

const OUTPUT_DIR = './app/src/main/assets/dicts';
const DOCS_DIR = './docs';

async function fileExists(filePath) {
  try {
    await fs.access(filePath);
    return true;
  } catch {
    return false;
  }
}

export async function main() {
  console.log('🏁 Starting LexiFlow Vocabulary Build Pipeline...');

  // 1. Download
  await runDownload();

  // 2. Parse
  await runParse();

  // 3. Merge
  await runMerge();

  // 4. Match Examples
  await runMatch();

  // 5. Validate
  const finalList = await runValidate();

  if (!finalList || finalList.length === 0) {
    console.error('❌ Failed to produce a valid vocabulary list!');
    return;
  }

  console.log('--- Step 6: Generating Android Asset Dictionaries & Reports ---');
  await fs.mkdir(OUTPUT_DIR, { recursive: true });
  await fs.mkdir(DOCS_DIR, { recursive: true });

  // Categorize words into books
  const booksMap = {
    cet4: [],
    cet6: [],
    ielts: [],
    toefl: [],
    coder: []
  };

  let totalDeduplicatedWords = finalList.length;
  let wordCategorizationStats = { cet4: 0, cet6: 0, ielts: 0, toefl: 0, coder: 0 };

  for (const entry of finalList) {
    const bookList = entry.books || [];
    
    // Format into legacy-compatible shape with embedded rich fields
    const firstEx = entry.examples[0] || {};
    const formatted = {
      word: entry.word,
      phonetic: entry.phonetic || '',
      translation: entry.translation || '',
      category: bookList[0] ? bookList[0].toUpperCase() : 'ALL',
      bookIds: bookList.join(','),
      exampleSentence: firstEx.originalEn || '',
      exampleTranslation: firstEx.translationZh || '',
      exampleSentenceCloze: firstEx.clozeEn || '',
      examplesJson: JSON.stringify(entry.examples)
    };

    // Distribute to matching books
    let matchedAny = false;
    for (const bName of Object.keys(booksMap)) {
      if (bookList.includes(bName)) {
        booksMap[bName].push(formatted);
        wordCategorizationStats[bName]++;
        matchedAny = true;
      }
    }
    
    // Default to high_freq or coder if none matched
    if (!matchedAny) {
      booksMap['coder'].push(formatted);
      wordCategorizationStats['coder']++;
    }
  }

  // Write files
  for (const [bName, items] of Object.entries(booksMap)) {
    const outputPath = path.join(OUTPUT_DIR, `${bName}.json`);
    await fs.writeFile(outputPath, JSON.stringify(items, null, 2), 'utf-8');
    console.log(`Saved ${items.length} words to asset dictionary: ${outputPath}`);
  }

  // Generate data stats file
  const stats = {
    totalUniqueWords: totalDeduplicatedWords,
    categoryBreakdown: wordCategorizationStats,
    timestamp: Date.now(),
    buildVersion: '2.0.0'
  };
  const statsPath = path.join(OUTPUT_DIR, 'vocabulary-stats.json');
  await fs.writeFile(statsPath, JSON.stringify(stats, null, 2), 'utf-8');
  console.log(`Saved stats to ${statsPath}`);

  // Generate build report
  const reportContent = `# LexiFlow Vocabulary Build Report

Generated on: ${new Date().toISOString()}

## Data Overview

- **Total Unique Unified Words**: ${totalDeduplicatedWords}
- **Build Version**: ${stats.buildVersion}

## Category Word Counts
- **CET-4**: ${wordCategorizationStats.cet4} words
- **CET-6**: ${wordCategorizationStats.cet6} words
- **IELTS**: ${wordCategorizationStats.ielts} words
- **TOEFL**: ${wordCategorizationStats.toefl} words
- **Coder**: ${wordCategorizationStats.coder} words

## Key Rules Followed:
1. **Uniqueness**: Words are saved strictly once globally in the database.
2. **Book Relation**: Multi-book relation is represented via the CSV \`bookIds\` string field.
3. **High-Quality Examples**: Fallback generators paired with live vocabulary-examples are used to guarantee no empty strings.
4. **Cloze Masking**: Morphological-aware sentence clozing masks target words completely without leaking lengths.
`;

  const reportPath = path.join(DOCS_DIR, 'vocabulary-build-report.md');
  await fs.writeFile(reportPath, reportContent, 'utf-8');
  console.log(`Saved markdown build report to ${reportPath}`);

  console.log('🎉 Vocabulary pipeline completed successfully!');
}

main().catch(console.error);
