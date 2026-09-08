# PicBrain

**Your Visual Memory**  
Package: `com.kareem.picbrain`

## v0.0.3 — Screenshot Understanding Foundation

This milestone adds the first searchable understanding layer on top of the read-only media index:

- Local OCR provider abstraction (`OcrEngine`) so OCR engines can be replaced without changing storage/search.
- Bundled ML Kit Text Recognition v2 first engine for offline text extraction.
- Raw OCR text and normalized OCR text stored separately.
- Non-destructive Room migration `1 → 2`; updating from v0.0.2 preserves the existing local index.
- Explicit OCR lifecycle: `NOT_PROCESSED`, `DONE`, `FAILED`.
- OCR engine/version, processed timestamp, and failure message persisted for audit/debugging.
- OCR survives media reconciliation when the source image has not changed.
- If `DATE_MODIFIED` changes, stale OCR is invalidated and the item becomes eligible for reprocessing.
- Batch processing of screenshots (25 at a time) to bound memory/CPU work.
- Exact/substring screenshot-text search.
- OCR result snippets visible directly below screenshot thumbnails.

### Important accuracy boundary

The first bundled engine uses ML Kit's Latin text recognizer. PicBrain does **not** claim complete Arabic OCR in this milestone. Arabic + mixed Arabic/English screenshots are an explicit next quality gate. The storage and engine abstraction are already designed so an Arabic-capable on-device provider can replace or augment the first recognizer without a database redesign.

### Privacy / safety contract

- Originals remain read-only.
- OCR happens on-device.
- No image or OCR text is uploaded by this milestone.
- No delete/move/rename gallery operations exist.

## v0.0.3 acceptance tests

1. Index screenshots.
2. Run OCR on a batch.
3. Confirm extracted English/Latin text is persisted after app restart/reconciliation.
4. Search a known word and verify the correct screenshot appears.
5. Search an absent word and verify no false filename-based match appears.
6. Modify/replace a screenshot and verify old OCR becomes invalidated.
7. Verify failed/unreadable images are marked `FAILED` without stopping the batch.
8. Benchmark Arabic and mixed Arabic/English screenshots separately before selecting the next OCR engine.
