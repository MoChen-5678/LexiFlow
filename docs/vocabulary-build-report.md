# LexiFlow Vocabulary Build Report

Generated on: 2026-06-23T19:48:14.666Z

## Data Overview

- **Total Unique Unified Words**: 4864
- **Build Version**: 2.0.0

## Category Word Counts
- **CET-4**: 1500 words
- **CET-6**: 1500 words
- **IELTS**: 1500 words
- **TOEFL**: 1500 words
- **Coder**: 1500 words

## Key Rules Followed:
1. **Uniqueness**: Words are saved strictly once globally in the database.
2. **Book Relation**: Multi-book relation is represented via the CSV `bookIds` string field.
3. **High-Quality Examples**: Fallback generators paired with live vocabulary-examples are used to guarantee no empty strings.
4. **Cloze Masking**: Morphological-aware sentence clozing masks target words completely without leaking lengths.
