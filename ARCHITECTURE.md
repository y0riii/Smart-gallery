# Smart Gallery — Architecture & System Guide

A detailed reference for how the app works and what each file is responsible for. Read this
alongside the inline comments in the code (the tricky parts have explanatory comments that this
document links to conceptually).

> Keep this file up to date when you change behavior. It is the single "how it all fits together"
> reference; the per-file table near the bottom is the map.

---

## 1. What the app is

Smart Gallery is an **offline, on-device** photo & video gallery with three "smart" capabilities,
all running locally with no network calls:

1. **Semantic search** — type a natural-language prompt ("dog on a beach") and CLIP finds matching
   photos by comparing the prompt's embedding to each photo's precomputed image embedding.
2. **OCR / document search** — text inside images is extracted at index time and searchable via a
   full-text (FTS) index.
3. **Face grouping (People)** — faces are detected, embedded, and clustered into "people" you can
   name, merge, and browse.

On top of that it has **Albums** (device folders), **AI Albums** (auto-categorized by a CLIP text
prompt), and **Collections** (manual user-created groupings).

Single-activity Jetpack Compose app, MVVM, Kotlin, Room for storage, WorkManager for background
processing, ONNX Runtime + ML Kit for the ML models.

---

## 2. The five tabs (UI top level)

`MainActivity` hosts a `HorizontalPager` of 5 pages, each with its own ViewModel:

| Tab | Screen | ViewModel | Data source |
|-----|--------|-----------|-------------|
| Home | `GalleryScreen` | `GalleryViewModel` | all device media (images + videos) |
| People | `PeopleFoldersScreen` | `PeopleViewModel` | `PersonFolderRepository` (Room `person`/`faces`) |
| AI Albums | `CategoryFoldersScreen` | `CategoryViewModel` | `CategoryFolderRepository` (Room `category`) |
| Albums | `AlbumsFoldersScreen` | `AlbumsViewModel` | `AlbumsFolderRepository` (raw MediaStore) |
| Collections | `CollectionsFoldersScreen` | `CollectionsViewModel` | `CollectionFolderRepository` (Room `collection`) |

The four folder tabs share a base `FoldersViewModel` and the generic `FoldersScreen` composable.

---

## 3. The data model (Room, `AppDatabase`)

Database name `media_ocr_db`, version 21. Schemas are now **exported** to `app/schemas/`
(`exportSchema = true`) so future changes can ship real migrations.

> ⚠️ The DB still uses `fallbackToDestructiveMigration`, so a schema/version change without a
> written `Migration` will wipe the user's index. Schema export is the first step toward fixing
> that — write real migrations before shipping a schema change to real users.

Entities:

- **`MediaEntity`** (`media_items`) — one row per indexed image. Holds the CLIP image `embedding`
  (`FloatArray`), the OCR `ocrText`, timestamp, and `mediaId` (the MediaStore id, used as PK).
  `isVideo` is always `false` today — videos are not indexed into this table (they come straight
  from MediaStore); the column is vestigial.
- **`FtsMediaEntity`** (`media_items_fts`) — FTS virtual table mirroring `ocrText` for fast
  full-text search.
- **`FaceEntity`** (`faces`) — one row per detected face: its `embedding`, bounding box, owning
  `mediaId` (FK → `media_items`, `CASCADE` delete), and nullable `personId` (set by clustering).
- **`PersonEntity`** (`person`) — a cluster of faces = a person. Holds the **summed** face
  embedding (`Embedding`, note capital E), a running `count`, a `name` (or `#p<n>` placeholder),
  and a `thumbnailPath` to a cropped face image on disk.
- **`CategoryEntity`** (`category`) — an AI Album. Holds the CLIP text `embedding` of its prompt.
- **`MediaCategoryCrossRef`** (`media_category_join`) — media↔category with a `similarity` score.
- **`CollectionEntity`** (`collection`) + **`CollectionMediaCrossRef`** (`collection_media_join`) —
  user collections and their membership (with `dateAddedMs`).

**Embedding storage:** `FloatArray` ⇆ `ByteArray` via `Converters` (big-endian `ByteBuffer`).
This byte layout is safety-critical — every similarity score depends on reading embeddings back
exactly as written. `ConvertersTest` guards the round-trip, including that the layout matches the
one `PersonDao.updateEmbedding` uses.

### DAOs

- **`MediaDao`** — media CRUD, the `deviceIds − dbIds` diffing support, and the two OCR search
  paths: `searchMediaFts` (FTS `MATCH`, multi-word joined with `AND`) and `searchMediaSimple`
  (`LIKE` fallback).
- **`PersonDao`** — person CRUD, incremental `count`/embedding updates, `@mention` name search
  (`getImagesByNames` / `getMediaIdsByNames` — intersection: photos containing *all* named people),
  and the SQL-side person ordering (`getAllNamesSortedFlow`).
- **`CategoryDao` / `CollectionDao`** — category/collection CRUD + cross-ref management.
- **`FaceDao`** — face CRUD and person-assignment updates. No embedding SQL — similarity is done
  in memory.
- **`DaoHelpers.associateMediaIds(...)`** — shared helper that stitches the "two flat queries +
  in-memory group" pattern used by Category/Collection/Person DAOs to build
  `List<Pair<Preview, List<Long>>>`. Previously duplicated in all three.

---

## 4. Indexing pipeline (the heart of the app)

Indexing keeps the Room DB in sync with the device and computes all the ML features. It runs in
the background via WorkManager, orchestrated by `GalleryService`.

### Who triggers it
- `MainActivity.onStart()` → `startIndexingWorkManager(force = true)` every time the app opens.
- `GalleryPeriodicTriggerWorker` — a periodic (6-hour) `WorkManager` job that enqueues indexing.
- `GalleryIndexerReceiver` — resume action from the pause/resume notification.

### The flow (`GalleryIndexerWorker.doWork`)
1. Promotes to a **foreground service** (shows the "Preparing photo indexing…" notification).
2. Acquires `GalleryService.mlExecutionLock` (see §6) and calls
   `GalleryService.indexImagesBackground { … }`.
3. On success, enqueues `FaceClusteringWorker`.
4. Progress/notification updates are **throttled** to at most every `PROGRESS_UPDATE_MS` (500 ms),
   always emitting the first and last item — firing `setProgress()` (a WorkManager DB write) and
   `notify()` (a cross-process call) per image was a real slowdown on large libraries.

### `GalleryService.indexImagesBackground`
1. Scans MediaStore (`ImageUtils.scanMediaStore`) → current device image ids.
2. Reads DB ids (`mediaDao.getAllMediaIds`).
3. **Diffs**: `idsToDelete = db − device`, `idsToAdd = device − db`.
4. Deletes obsolete media (`deleteImageFromDb`, which also decrements/removes owning persons).
5. Processes new media via `processAndInsertImages`.

### `processAndInsertImages` (per new image)
For each image it runs three ML models concurrently on `Dispatchers.Default`:
- `ClipImageEncoder.getImageFeatures` → the image embedding.
- `OcrProcessor.recognizeText` → OCR text.
- `FaceDetectionProcessor.detectFaces` → faces (then `FaceEncoder.getFaceFeatures` per face).

It also computes category cross-refs by comparing the image embedding to each existing category
embedding (category list is intentionally re-queried per image so a category created mid-run is
picked up).

**Batched writes:** results are buffered and committed in one transaction every
`INDEX_BATCH_SIZE` (24) images instead of one transaction per image — the per-commit fsync was a
dominant cost. Within each transaction, media is inserted before faces/cross-refs so the face FK
constraint holds. A `NonCancellable` final flush persists the tail even on cancellation. If killed
mid-batch, the unflushed images are simply re-indexed next run (indexing is a diff — see §7).

**Pause:** the notification has a pause button (`GalleryIndexerReceiver` → `isPaused` flag). When
paused, the loop flushes pending writes, releases the ML models, and waits.

### The ML models (`ml/`)
- **`ClipImageEncoder`** — CLIP image tower (ONNX, `image_model.ort`), 256×256 input, outputs a
  normalized embedding.
- **`ClipTextEncoder`** — CLIP text tower (ONNX, `text_model.ort`) + `ClipTokenizer` (BPE). Used
  for search prompts and AI-album prompts. Long-lived singleton (see `GalleryService` companion).
- **`FaceEncoder`** — MobileFaceNet (ONNX, `MobileFaceNet.ort`), 112×112, averages an image with
  its horizontal flip for a more robust embedding.
- **`FaceDetectionProcessor`** — ML Kit face detection + landmark-based alignment (Umeyama
  similarity transform) to a canonical 112×112 pose before encoding.
- **`OcrProcessor`** — ML Kit Latin text recognition.
- **`OrtAcceleration`** — **per-model, once-per-install benchmark** that decides CPU vs NNAPI. On
  first use of each model it times a few dummy inferences on both providers, keeps the faster one,
  and caches the decision in SharedPreferences (`ort_acceleration_prefs`). This exists because
  NNAPI is a big win on some devices and a net loss on others. Encoders never close the shared
  `OrtEnvironment` singleton (only their own session), since closing it would break other encoders.

---

## 5. Face clustering (`FaceClusteringWorker` → `GalleryService.createClusters`)

Runs after indexing, under the same `mlExecutionLock`.

- **First run / forced recluster** (`first_clustering_run_completed` pref false, or triggered by
  `forceRecluster`): runs full **Chinese Whispers** graph clustering (`ChineseWhispers.cluster`) on
  all face embeddings, builds people, generates thumbnails, and commits **everything in one atomic
  transaction** (wipe + rebuild together). The old clustering stays intact on disk until that
  commit, so a crash mid-computation never leaves an empty People tab (see §7). `ChineseWhispers`
  uses a **seeded RNG** so results are reproducible.
- **Incremental run** (subsequent): only faces with `personId == null` are processed. Each is
  assigned to the nearest existing person centroid (if similarity ≥ `ASSIGN_THRESHOLD`) or becomes
  a new singleton person. Applied in a single transaction.
- After either path, `cleanupOrphanThumbnails()` deletes thumbnail files on disk no longer
  referenced by any person (from re-clustering or interrupted runs).

**People ordering** is defined once in `folders/PersonSort.kt` (favorites → named A–Z →
`#p` placeholders numerically) and reused by the repository and `PeopleViewModel`. (The SQL query
`PersonDao.getAllNamesSortedFlow` mirrors the same rule for the `@mention` dropdown.)

---

## 6. Concurrency: can two workers run at once?

**No — ML work is serialized.** Both `GalleryIndexerWorker` and `FaceClusteringWorker` wrap their
work in the process-wide `GalleryService.mlExecutionLock` mutex. WorkManager unique-work names
(`GalleryIndexing_OneTime`, `GalleryClustering_OneTime`) prevent duplicate copies of the *same*
worker; the mutex prevents indexing and clustering from computing simultaneously (the second one
suspends until the first releases the lock). `isIndexingRunning` / `isClusteringRunning` flags let
`forceRecluster` bail out early to avoid races.

---

## 7. Crash / shutdown safety

- **Indexing is fully resumable.** Writes are atomic (batched transactions); on restart the
  device-vs-DB diff recomputes what's missing. WorkManager reschedules a killed worker (and
  reschedules pending work after reboot). Worst case after a kill: up to `INDEX_BATCH_SIZE`
  in-flight images are re-processed next run.
- **Incremental clustering is safe** — single atomic transaction; uncommitted work just leaves the
  faces `personId == null` for next run.
- **First-run/forced clustering is now safe too** — the redundant pre-wipe was removed, so the
  wipe-and-rebuild is atomic. A crash during the (long) Chinese Whispers computation leaves the
  previous clustering fully intact; the `first_clustering_run_completed` flag is only set after the
  commit, so an interrupted run simply re-clusters next time. Custom names are only lost on a
  *forced* recluster, which is by-design.

---

## 8. Search paths (`GalleryService`)

- **Semantic (CLIP):** `search(prompt)` → embed prompt, dot-product against all media embeddings
  (served from an in-memory cache, `getAllMediaCached`, invalidated on insert/delete so repeat
  searches don't re-deserialize every BLOB), sort by score. `@name` mentions filter to photos
  containing those people first (`findNamesInPrompt` + `PersonDao.getImagesByNames`).
- **Document (OCR):** `searchDocuments(text)` → FTS (`searchMediaFts`) or `LIKE` fallback.
- **Date-only:** merges images + videos from MediaStore by date.
- **Within a folder:** `searchWithin(mediaIds, …)` applies prompt/date/sort to a restricted id set
  (used by all folder repositories).

---

## 9. Deletion flow (`DeletableViewModel`)

Shared by all ViewModels. Uses `MediaStore.createDeleteRequest` (Android R+) which requires a
system confirmation dialog, surfaced via an `IntentSender` + a monotonically increasing
`intentSenderVersion` counter (so Compose reliably re-launches). Large selections are split into
batches of 200 to avoid `TransactionTooLargeException`. Some ROMs return `CANCELED` even on
success, so it polls `isUriDeleted` before giving up. After device deletion, `finalizeDeleteImages`
cleans up the DB (which cascades faces and adjusts person counts).

---

## 10. File-by-file map

### Top level (`com.example.gallery`)
- `MainActivity.kt` — single activity; hosts the 5-tab pager, owns all ViewModels, requests
  permissions & battery-optimization exemption, schedules periodic indexing.
- `GalleryService.kt` — **central orchestrator**: indexing, search, clustering, deletion, the
  text-encoder singleton, the media cache, and the ML execution lock.
- `SortMode.kt` — `RELEVANCE` / `DATE_DESC` enum.

### `db/`
- `AppDatabase.kt` — Room database definition, singleton builder.
- `Converters.kt` — `FloatArray` ⇆ `ByteArray` embedding serialization.
- `GalleryIndexerWorker.kt` — foreground worker running indexing; pause/resume notification;
  throttled progress.
- `FaceClusteringWorker.kt` — foreground worker running clustering.
- `GalleryPeriodicTriggerWorker.kt` — periodic trigger that enqueues indexing.
- `GalleryIndexerReceiver.kt` — broadcast receiver for the pause/resume notification actions.
- `db/daos/` — `MediaDao`, `PersonDao`, `CategoryDao`, `CollectionDao`, `FaceDao`, and
  `DaoHelpers.kt` (shared `associateMediaIds`).
- `db/entities/` — the Room entities listed in §3.
- `db/previews/` — lightweight projection classes (`CategoryPreview`, `CollectionPreview`,
  `PersonPreview`, `PersonMediaRef`, `MediaDateInfo`) for cheaper queries.

### `ml/`
- `OrtAcceleration.kt` — CPU-vs-NNAPI per-model benchmark + cache.
- `ml/text/TextEncoderProvider.kt` — owns the single, lazily-built, process-wide `ClipTextEncoder`
  used by search (extracted from `GalleryService`'s companion so the lazy-init concurrency is
  isolated).
- `ml/image/ClipImageEncoder.kt`, `ml/text/ClipTextEncoder.kt`, `ml/text/ClipTokenizer.kt`,
  `ml/face/FaceEncoder.kt`, `ml/face/FaceDetectionProcessor.kt`, `ml/face/ChineseWhispers.kt`,
  `ml/ocr/OcrProcessor.kt` — described in §4/§5.

### `folders/`
- `FolderSource.kt` — interface (`getFoldersFlow` / `getImagesFlow`) implemented by all repos.
- `AlbumsFolderRepository.kt` — the only repo reading raw MediaStore (reactive via ContentObserver).
- `CategoryFolderRepository.kt`, `CollectionFolderRepository.kt`, `PersonFolderRepository.kt` —
  Room-backed repos.
- `FolderItem.kt` — the folder tile model (+ `padToFour` thumbnail helper).
- `PersonSort.kt` — single source of truth for people ordering.

### `viewModels/`
- `DeletableViewModel.kt` — base: multi-select, bulk share, bulk delete flow.
- `FoldersViewModel.kt` — base for the four folder tabs: folder list, open-folder search, favorites,
  scroll restoration.
- `GalleryViewModel.kt` — Home tab: all-media flow, search, date-header timestamps.
- `PeopleViewModel.kt`, `CategoryViewModel.kt`, `AlbumsViewModel.kt`, `CollectionsViewModel.kt` —
  tab-specific behavior (rename/merge, create category, create collection, etc.).
- `viewModels/factories/` — `ViewModelProvider.Factory` boilerplate.

### `components/` (Compose UI)
- `GalleryScreen.kt`, `ImageGridScreen.kt`, `ImageGrid.kt` — home grid, column control, date
  headers, drag-select, selection toolbar.
- `FoldersScreen.kt` — the generic folder grid + open-folder view wrapped by all four folder tabs.
- `FullScreenImage.kt` — full-screen pager with zoom/pan, swipe-to-dismiss, video playback,
  share/delete.
- `SearchBar.kt`, `SearchInputField.kt` (@mention autocomplete), `SearchModeToggle.kt`.
- `FolderTile.kt`, `CustomDialog.kt`, `CollectionPickerDialog.kt`, `CreateButton.kt`, and the
  per-tab folder screens.
- `components/tutorial/` — **first-time tutorial**. `Tutorial.kt` holds the per-tab copy
  (`TutorialTab` enum, `tutorialContentFor`) and `TutorialPrefs` (per-tab "seen" flags in a
  `tutorial_prefs` SharedPreferences file). `TutorialOverlay.kt` is the modal explainer card.
  `MainActivity.GalleryApp` shows the overlay the first time (per install) the user lands on each
  navbar tab, keyed off `currentTab`; dismissing marks that tab seen so it never repeats.
  `TutorialPrefs.resetAll()` re-arms every tutorial (useful for QA or a future "replay" option).

### `utils/`
- `ImageUtils.kt` — MediaStore image scan, bitmap decode/downsample, crop, thumbnail create/delete.
- `VideoUtils.kt` — MediaStore video scan/date-filter, video URI helpers, thumbnail loader.
- `VectorUtils.kt` — vector math (dot product, normalize, add/subtract/divide, distance).

### `ui/theme/`
- `AppConfig.kt` — centralized design tokens (spacing, radii, animation durations, colors).
- `Color.kt`, `Theme.kt`, `Type.kt` — Material theme setup.

### Tests
- `test/` (JVM): `VectorUtilsTest`, `ChineseWhispersTest`, `db/ConvertersTest`.
- `androidTest/` (instrumented, real in-memory Room): `MediaDaoTest`, `PersonDaoTest`,
  `FaceAndClusteringTest`, `OcrProcessorTest`.
- Gaps: `ImageUtils` and `ClipTokenizer` have no dedicated tests (both need instrumented tests —
  Bitmap / asset access).

---

## 11. Known remaining considerations
- `fallbackToDestructiveMigration` still risks data loss on schema change — write real migrations.
- `MediaEntity.isVideo` is vestigial (always false).
- Removing `addNnapi` unconditionally was reverted in favor of the `OrtAcceleration` benchmark; if a
  device benchmarks slower on NNAPI it will correctly stick to CPU.
