# Smart Gallery — Detailed Architecture & System Reference

The complete, current reference for how Smart Gallery is built: what every subsystem does, how
data flows through the app, the concurrency and crash-safety guarantees, and a file-by-file map of
all ~93 Kotlin source files.

> This document supersedes the shorter `ARCHITECTURE.md`. When you change behavior, update this
> file — it is meant to be the single "how it all fits together" reference.

---

## Table of contents

1. [What the app is](#1-what-the-app-is)
2. [Tech stack](#2-tech-stack)
3. [Big picture — the layers](#3-big-picture--the-layers)
4. [Startup & lifecycle](#4-startup--lifecycle)
5. [Navigation & the five tabs](#5-navigation--the-five-tabs)
6. [The data model (Room)](#6-the-data-model-room)
7. [GalleryService — the central orchestrator](#7-galleryservice--the-central-orchestrator)
8. [The background pipeline: index → cluster → Arabic OCR](#8-the-background-pipeline-index--cluster--arabic-ocr)
9. [Indexing in detail](#9-indexing-in-detail)
10. [Face clustering in detail](#10-face-clustering-in-detail)
11. [Arabic OCR pass in detail](#11-arabic-ocr-pass-in-detail)
12. [The ML subsystem](#12-the-ml-subsystem)
13. [Concurrency, locking & priority](#13-concurrency-locking--priority)
14. [Crash & shutdown safety](#14-crash--shutdown-safety)
15. [Notifications, pause & resume](#15-notifications-pause--resume)
16. [Search subsystem](#16-search-subsystem)
17. [The Folders abstraction (tabs 1–4)](#17-the-folders-abstraction-tabs-14)
18. [ViewModels](#18-viewmodels)
19. [UI components](#19-ui-components)
20. [Settings & theming](#20-settings--theming)
21. [Deletion flow](#21-deletion-flow)
22. [Caching](#22-caching)
23. [Tutorial system](#23-tutorial-system)
24. [Utilities](#24-utilities)
25. [Complete file-by-file map](#25-complete-file-by-file-map)
26. [Cross-cutting invariants](#26-cross-cutting-invariants)
27. [Known limitations & gotchas](#27-known-limitations--gotchas)

---

## 1. What the app is

Smart Gallery is a **fully offline, on-device** photo & video gallery. Every "smart" feature runs
locally with **zero network calls**. Three headline capabilities:

1. **Semantic search** — type a natural-language prompt ("dog on a beach") and a CLIP model matches
   it against each photo's precomputed image embedding.
2. **OCR / document search** — text inside images is extracted at index time (English via ML Kit,
   optionally Arabic via Tesseract) and searchable through a full-text (FTS) index.
3. **Face grouping (People)** — faces are detected, embedded, and clustered into "people" you can
   name, merge, favorite, and browse.

On top of those it offers:

- **Albums** — the device's real photo folders (Camera, Screenshots, …), read live from MediaStore.
- **AI Albums** — self-filling albums defined by a CLIP text prompt ("food", "cars").
- **Collections** — manual, hand-curated groupings.

---

## 2. Tech stack

| Concern | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose (single-activity), Material 3 |
| Architecture | MVVM |
| Persistence | Room (SQLite) + FTS4 virtual table |
| Background work | WorkManager (foreground `CoroutineWorker`s) |
| Neural nets | ONNX Runtime (CLIP image/text, MobileFaceNet) |
| ML utilities | ML Kit (Latin OCR, face detection), Tesseract4Android (Arabic OCR) |
| Image loading | Coil 3 |
| Video playback | Media3 / ExoPlayer |
| Async | Kotlin coroutines + Flow |

---

## 3. Big picture — the layers

```
┌──────────────────────────────────────────────────────────────────────┐
│  UI (Jetpack Compose)                                                  │
│  MainActivity → GalleryApp (pager, nav bar) → per-tab Screens          │
│  GalleryScreen · FoldersScreen · FullScreenImage · SettingsDialog · …  │
└───────────────▲──────────────────────────────────────────────────────┘
                │ state (Compose State / StateFlow), events (lambdas)
┌───────────────┴──────────────────────────────────────────────────────┐
│  ViewModels (MVVM)                                                     │
│  GalleryViewModel · FoldersViewModel(+4 subclasses) · DeletableVM base │
└───────────────▲──────────────────────────────────────────────────────┘
                │ suspend calls / Flows
┌───────────────┴───────────────┐   ┌──────────────────────────────────┐
│  Folder repositories          │   │  GalleryService (singleton)       │
│  Albums(MediaStore) · Person  │──▶│  indexing · search · clustering · │
│  Category · Collection (Room) │   │  deletion · media cache · ML lock │
└───────────────────────────────┘   └───────▲──────────────▲───────────┘
                                             │              │
                        ┌────────────────────┘              │
                        │                                   │
        ┌───────────────┴───────────┐        ┌──────────────┴───────────────┐
        │  Room (AppDatabase)        │        │  ML subsystem (ml/)           │
        │  entities · DAOs · FTS     │        │  CLIP · MobileFaceNet · OCR · │
        │  Converters (embeddings)   │        │  ChineseWhispers · FaceClusterer│
        └────────────────────────────┘        │  OrtAcceleration (CPU/NNAPI)  │
                                              └───────────────────────────────┘
        ┌────────────────────────────────────────────────────────────────┐
        │  WorkManager workers (background)                                │
        │  GalleryIndexerWorker (indexing OR Arabic OCR) ·                 │
        │  FaceClusteringWorker · GalleryPeriodicTriggerWorker             │
        │  GalleryIndexerReceiver (pause/resume notification actions)      │
        └────────────────────────────────────────────────────────────────┘
```

The key structural idea: **workers do no logic of their own** — they set up a foreground
notification and delegate to `GalleryService`, which owns all the real work and the locks that
serialize it. The UI never talks to workers or ML directly; it goes through ViewModels →
(repositories/`GalleryService`).

---

## 4. Startup & lifecycle

**`SmartGalleryApp`** (`Application`) is the process entry point. Its one job is to configure Coil's
singleton `ImageLoader` with a generous in-memory bitmap cache (30% of app memory) so thumbnails
scroll smoothly and survive recycling. Registered via `android:name=".SmartGalleryApp"` in the
manifest.

**`MainActivity.onCreate`**:
- Requests a one-time battery-optimization exemption (so background indexing isn't throttled).
- Calls `setContent { GalleryTheme(darkTheme = …) { GalleryApp(...) } }`. The `darkTheme` value is
  computed from `galleryViewModel.themeMode` (see §20), so a theme change recomposes the whole app.

**`MainActivity.onStart`** (every time the app comes to the foreground):
- Clears the pause flag, calls `galleryService.startIndexingWorkManager(force = true)`, and
  schedules the 6-hour periodic indexing (`ExistingPeriodicWorkPolicy.KEEP`).

**Permissions** — `getPermissionsToRequest()` returns the right set per Android version
(`READ_MEDIA_IMAGES/VIDEO` + `VISUAL_USER_SELECTED` on 13+, `READ_EXTERNAL_STORAGE` on ≤12). Until a
media permission is granted, `GalleryApp` shows `PermissionRequestScreen` instead of the pager, and
the nav bar / progress bar are hidden.

---

## 5. Navigation & the five tabs

`MainActivity.GalleryApp` hosts a `HorizontalPager` of 5 pages behind a bottom `NavigationBar`. Page
index = tab index, and that ordering also matches the `TutorialTab` enum.

| # | Tab | Screen | ViewModel | Data source |
|---|-----|--------|-----------|-------------|
| 0 | Home | `GalleryScreen` | `GalleryViewModel` | all device media (images + videos), semantic/OCR/date search |
| 1 | People | `PeopleFoldersScreen` | `PeopleViewModel` | `PersonFolderRepository` (Room `person`/`faces`) |
| 2 | AI Albums | `CategoryFoldersScreen` | `CategoryViewModel` | `CategoryFolderRepository` (Room `category`) |
| 3 | Albums | `AlbumsFoldersScreen` | `AlbumsViewModel` | `AlbumsFolderRepository` (**raw MediaStore**) |
| 4 | Collections | `CollectionsFoldersScreen` | `CollectionsViewModel` | `CollectionFolderRepository` (Room `collection`) |

Tabs 1–4 all share the base `FoldersViewModel` and the generic `FoldersScreen` composable — they
differ only in their repository and a few tab-specific actions (rename/merge, create category, etc.).

`GalleryApp` also owns cross-cutting UI state derived from whichever tab is active: `isFullScreen`,
`isSelecting`, `isSelectingFolders`, `isFolderOpen` — these gate pager swiping (`pagerScrollEnabled`)
and whether the nav bar/progress bar show. The **Collection picker** dialog and the **first-time
tutorial overlay** are also hosted here.

The bottom-of-screen **`LinearProgressIndicator`** is bound to `GalleryService.progress`
(`StateFlow<Float?>`), so any background pass (indexing/Arabic) drives it.

---

## 6. The data model (Room)

**`AppDatabase`** — database name `media_ocr_db`, **version 22**, `exportSchema = true` (schemas
written to `app/schemas/`). Built as a process-wide singleton via double-checked locking. Migrations
are real now: `MIGRATION_21_22` adds the `arabic_ocr_done` table; the builder uses
`.addMigrations(MIGRATION_21_22).fallbackToDestructiveMigration(false)`.

### Entities (`db/entities/`)

| Entity | Table | Purpose |
|---|---|---|
| `MediaEntity` | `media_items` | one row per indexed **image**: CLIP `embedding` (`FloatArray`), `ocrText`, `timestampMs`, PK `mediaId` (the MediaStore id). `isVideo` is vestigial (always false — videos aren't indexed into Room). |
| `FtsMediaEntity` | `media_items_fts` | FTS4 virtual table mirroring `ocrText` for fast full-text search. |
| `FaceEntity` | `faces` | one detected face: `embedding`, bounding box (`boxLeft/Top/Right/Bottom`), owning `mediaId` (FK → `media_items`, CASCADE), nullable `personId` (set by clustering). |
| `PersonEntity` | `person` | a face cluster = a person: the **summed** face embedding (`Embedding`, capital E), running `count`, `name` (or `#p<n>` placeholder), `thumbnailPath` (cropped face on disk), `thumbnailSize`. |
| `CategoryEntity` | `category` | an AI Album: the CLIP text `embedding` of its prompt. |
| `MediaCategoryCrossRef` | `media_category_join` | media↔category with a `similarity` score. |
| `CollectionEntity` | `collection` | a user collection. |
| `CollectionMediaCrossRef` | `collection_media_join` | collection membership (with `dateAddedMs`). |
| `ArabicOcrDoneEntity` | `arabic_ocr_done` | one row per `mediaId` the Arabic OCR pass has already scanned — the "done marker" that makes Arabic incremental & idempotent. |

### Embedding storage — safety-critical

`Converters` serializes `FloatArray ⇆ ByteArray` via a big-endian `ByteBuffer`. Every similarity
score depends on reading embeddings back **byte-for-byte** as written, and the layout must match the
one `PersonDao.updateEmbedding` writes. `ConvertersTest` guards this round-trip.

### DAOs (`db/daos/`)

- **`MediaDao`** — media CRUD; the diff support (`getAllMediaIds`); OCR search (`searchMediaFts` FTS
  `MATCH`, multi-word `AND`-joined, and `searchMediaSimple` `LIKE` fallback); and the Arabic-tracking
  queries: `getMediaPendingArabic` (rows not in `arabic_ocr_done`, returns `MediaOcrRef`),
  `countMediaPendingArabic`, `markArabicOcrDone`, `deleteArabicOcrDone`.
- **`PersonDao`** — person CRUD, incremental `count`/embedding/thumbnail updates, `@mention` name
  search (`getImagesByNames` = photos containing **all** named people), SQL-side ordering
  (`getAllNamesSortedFlow`).
- **`CategoryDao` / `CollectionDao`** — category/collection CRUD + cross-ref management.
- **`FaceDao`** — face CRUD and person-assignment updates. No embedding SQL — similarity is done in
  memory (see clustering).
- **`DaoHelpers.associateMediaIds(...)`** — shared "two flat queries + in-memory group" helper used
  by Category/Collection/Person DAOs to build `List<Pair<Preview, List<Long>>>`.

### Previews (`db/previews/`)

Lightweight projection classes for cheaper queries: `CategoryPreview`, `CollectionPreview`,
`PersonPreview`, `PersonMediaRef`, `MediaDateInfo`, and `MediaOcrRef` (id + ocrText, used by the
Arabic pass).

---

## 7. GalleryService — the central orchestrator

`GalleryService` (`GalleryService.kt`, the largest file) is a **process-wide singleton** (private
constructor + `getInstance(context)` with double-checked locking; stores `applicationContext` so it
never leaks a short-lived context). Everything single-instance about the app lives here or in its
companion.

**Companion (shared, process-wide) state:**
- `progress: MutableStateFlow<Float?>` — drives the UI progress bar.
- `cachedMedia` + `invalidateMediaCache()` — the search embedding cache (see §22).
- `mlExecutionLock: Mutex` — serializes all heavy background ML work (see §13).
- `isIndexingRunning` / `isClusteringRunning` — status flags used to avoid races.
- `indexingRequested` — priority flag: the Arabic pass yields when this is set (see §13).
- Tunables: `INDEX_BATCH_SIZE = 10`, `SEARCH_RELEVANCE_THRESHOLD = 0.2f`,
  `CATEGORY_MATCH_THRESHOLD = 0.22f`, and the pref keys `PREF_ARABIC_OCR`, `PREF_AUTO_RECLUSTER`,
  `PREF_THEME_MODE`.

**Instance responsibilities**, grouped in file order:
- **Work scheduling** — `startIndexingWorkManager(force)`, `startArabicOcrWorkManager()`,
  `forceRecluster()`, `isUriDeleted()`.
- **Indexing** — `indexImagesBackground()`, `processAndInsertImages()`, `deleteImageFromDb()`.
- **Arabic OCR pass** — `runArabicOcrPass()`, `hasPendingArabicOcr()`.
- **Clustering** — `createClusters()`, which **delegates to `FaceClusterer`** (extracted class).
- **MediaStore queries** — `getAllDeviceMedia()`, date-filter helpers, `mergeMediaByDate()`.
- **AI categories** — `createCategory()`.
- **Search** — `search()`, `searchWithin()`, `searchDocuments()`, `findNamesInPrompt()`.
- **Deletion** — prepare/finalize single & bulk (mostly inherited via `DeletableViewModel`).

Its heavy dependencies (Room DB, the CLIP text encoder via `TextEncoderProvider`) are themselves
singletons, so the coordinator being a singleton closes the loop — there is exactly one of each.

---

## 8. The background pipeline: index → cluster → Arabic OCR

The three background passes always run in a **strict order**, each chained on the previous one's
success, and all serialized by `mlExecutionLock`:

```
 trigger (app open / 6h / resume / enable-Arabic / forceRecluster)
    │
    ▼
 GalleryIndexerWorker (reOcrOnly=false)  ── indexImagesBackground()
    │  on success enqueues ▼
 FaceClusteringWorker  ── createClusters() → FaceClusterer
    │  on success, IF Arabic enabled AND pending work, enqueues ▼
 GalleryIndexerWorker (reOcrOnly=true)   ── runArabicOcrPass()
```

- **Indexing** is the entry point and is **incremental** (a device-vs-DB diff). It only ever
  processes *new* photos and removes deleted ones.
- **Clustering** runs next; incremental unless a full re-cluster is due.
- **Arabic OCR** is the **lowest-priority, opt-in** tail step. It only runs if the user enabled it
  *and* there are unscanned images (`hasPendingArabicOcr`). It is incremental via the
  `arabic_ocr_done` marker table.

Unique work names keep each stage de-duplicated: `GalleryIndexing_OneTime`,
`GalleryClustering_OneTime`, `GalleryArabicOcr_OneTime`.

---

## 9. Indexing in detail

**Trigger points:** `MainActivity.onStart` (`force = true`, REPLACE), `GalleryPeriodicTriggerWorker`
(every 6 h, KEEP — routed through `startIndexingWorkManager` so it also raises the priority flag),
`GalleryIndexerReceiver` resume, `updateArabicOcrEnabled`, `forceRecluster`.

**`GalleryIndexerWorker.doWork` (reOcrOnly = false):**
1. Reads `reOcrOnly` from input data; sets `isArabicPass` accordingly (drives notification wording).
2. Promotes to a **foreground service** ("Preparing photo indexing…").
3. Acquires `mlExecutionLock`, sets `isIndexingRunning = true`, and calls
   `service.indexImagesBackground(onProgress)`.
4. On completion clears `indexingRequested` (the priority request is satisfied).
5. Enqueues `FaceClusteringWorker` (KEEP).
6. Progress/notification updates are **throttled** to ≥ `PROGRESS_UPDATE_MS` (500 ms), always
   emitting the first & last item (per-image `setProgress`/`notify` was a measurable slowdown).

**`GalleryService.indexImagesBackground`:**
1. Scans MediaStore (`ImageUtils.scanMediaStore`) → current device image ids.
2. Reads DB ids (`mediaDao.getAllMediaIds`).
3. Diffs: `idsToDelete = db − device`, `idsToAdd = device − db`.
4. Deletes obsolete media via `deleteImageFromDb` (cascades faces, cleans person counts, drops the
   `arabic_ocr_done` marker, invalidates the media cache).
5. Processes new media via `processAndInsertImages`.

**`processAndInsertImages` (per new image):** runs three models concurrently on
`Dispatchers.Default`:
- `ClipImageEncoder.getImageFeatures` → image embedding.
- `OcrProcessor.recognizeText` → **English (Latin)** OCR text, run through `ArabicTextNormalizer`
  (a no-op for Latin, but keeps stored-text format consistent with what the Arabic pass later writes).
- `FaceDetectionProcessor.detectFaces` → faces, then `FaceEncoder.getFaceFeatures` per aligned face.

It also computes **category cross-refs** by dot-producting the image embedding against each existing
category embedding (`> CATEGORY_MATCH_THRESHOLD`). The category list is **intentionally re-queried
per image** so a category created mid-run is picked up.

**Batched writes:** results buffer and commit in one transaction every `INDEX_BATCH_SIZE` (**10**)
images. Within a transaction, media is inserted before faces/cross-refs (FK ordering). A
`NonCancellable` final flush persists the tail on cancellation. If killed mid-batch, the unflushed
(<10) images are simply re-indexed next run — safe because indexing is a diff.

**Pause:** the loop polls `GalleryIndexerWorker.isPaused`; when paused it flushes pending writes,
releases the four ML models (to free memory), nulls the progress, and waits in a 1-second poll loop
until resumed.

---

## 10. Face clustering in detail

Lives in **`FaceClusterer`** (`ml/face/FaceClusterer.kt`), extracted from `GalleryService`;
`GalleryService.createClusters()` just delegates to it. Runs inside `mlExecutionLock` (held by
`FaceClusteringWorker`), so it never overlaps indexing.

**Full (re)cluster** — runs when: first ever clustering, no persons exist, `forceRecluster`, **or**
`facesSinceFull >= FULL_RECLUSTER_THRESHOLD` (300) **and** the user's auto-recluster setting is on
(`PREF_AUTO_RECLUSTER`, default true). It:
- Runs **Chinese Whispers** (`ChineseWhispers.cluster`, seeded RNG for reproducibility) over all
  normalized face embeddings.
- **Preserves identity**: `computeCarriedIdentity` maps each new cluster to the old person that
  contributed the most of its faces, carrying that person's **id + name** — so user names *and*
  favorites (keyed by person id) survive; other clusters get fresh ids above the current max.
- Builds people + face thumbnails and commits the **entire wipe-and-rebuild in one atomic
  transaction**, so a crash mid-computation never empties the People tab. The
  `first_clustering_run_completed` flag and the drift counter are set **only after** commit.

**Incremental run** (the common case) — only faces with `personId == null` are processed. Each new
face is matched by **nearest-neighbor**: the person whose **top-3 member-face similarities** average
highest, if that average clears `ChineseWhispers.EDGE_THRESHOLD` (0.55, the single shared face↔face
cutoff); otherwise it becomes a new singleton person. Matches against **actual member embeddings**
(not a drifting centroid), grown in-loop so faces assigned earlier in the run are matchable by later
ones. The accumulated new-face count is persisted so a full recluster eventually corrects drift.
Applied in one transaction.

After either path, `cleanupOrphanThumbnails()` deletes on-disk `thumb_*.jpg` files no longer
referenced by any person.

**People ordering** is defined once in `folders/PersonSort.kt` (favorites → named A–Z → `#p`
placeholders numerically) and reused by the repository, `PeopleViewModel`, and mirrored in SQL by
`PersonDao.getAllNamesSortedFlow` for the `@mention` dropdown.

Clustering is deliberately **not pausable** — it commits atomically, so there's no safe mid-point to
stop at.

---

## 11. Arabic OCR pass in detail

Arabic OCR (`ArabicOcrProcessor`, Tesseract) has **no ML Kit equivalent**, is slow, and is therefore
**opt-in** (Settings toggle, off by default) and structured as a **separate final pipeline step** —
never inline in indexing.

**`GalleryService.runArabicOcrPass`** (invoked by `GalleryIndexerWorker` with
`KEY_RE_OCR_ONLY = true`):
1. Loads `mediaDao.getMediaPendingArabic()` — exactly the rows **not** in `arabic_ocr_done`. So it
   scans the whole library once when first enabled, then only new photos, and never re-scans.
2. For each pending image: decode bitmap → `ArabicOcrProcessor.recognizeText` (grayscale
   preprocessing, drops results below `MIN_CONFIDENCE = 60` to avoid indexing garbage).
3. Appends any Arabic text to the existing English text, re-normalizes via `ArabicTextNormalizer`,
   and writes `updateOcrText` **+** `markArabicOcrDone` in a **single `db.withTransaction`** — so an
   interruption can't append text without the done-marker (which would double-append next run).

**Incremental & idempotent** — because "done" is a per-image marker written atomically with the text,
delete-then-re-add of an image (its marker is dropped in `deleteImageFromDb`) and mid-pass kills both
behave correctly with no double-scan and no misses.

**Pausable** — the pass runs through `GalleryIndexerWorker`, so it inherits the foreground
notification + pause button. The loop honors `isPaused` (spins in place, keeping the single Tesseract
model alive so resume is instant). The `isArabicPass` companion flag makes every notification say
"Arabic text scan" instead of "photo indexing".

**Yields to indexing** — see §13.

`ArabicTextNormalizer` collapses Arabic to its 28 base letters (strips tashkeel/tatweel, unifies
alef/hamza/teh-marbuta/alef-maksura). The same normalizer is applied to the OCR search query, and
Arabic queries use the `LIKE` path (FTS's `simple` tokenizer doesn't index non-ASCII).

---

## 12. The ML subsystem

All models load from bundled `.ort`/asset files and run via **ONNX Runtime** (except ML Kit and
Tesseract). Located under `ml/`.

- **`ClipImageEncoder`** (`ml/image/`) — CLIP image tower (`image_model.ort`), 256×256 input, outputs
  a normalized image embedding.
- **`ClipTextEncoder`** (`ml/text/`) — CLIP text tower (`text_model.ort`) + **`ClipTokenizer`** (BPE).
  Long-lived process-wide singleton owned by **`TextEncoderProvider`** (extracted so the fiddly
  lazy-init concurrency — UI first-search vs preload vs workers — is isolated behind `initLock`, with
  graceful null on load failure and preload via `ensureInitialized`).
- **`FaceEncoder`** (`ml/face/`) — MobileFaceNet (`MobileFaceNet.ort`), 112×112, averages an image
  with its horizontal flip for a more robust embedding.
- **`FaceDetectionProcessor`** (`ml/face/`) — ML Kit face detection + landmark-based alignment
  (Umeyama similarity transform) to a canonical 112×112 pose before encoding.
- **`ChineseWhispers`** (`ml/face/`) — the graph clustering algorithm; `EDGE_THRESHOLD = 0.55f`
  (public, shared with incremental assignment), seeded RNG (`SHUFFLE_SEED`) for reproducible clusters.
- **`FaceClusterer`** (`ml/face/`) — orchestrates full/incremental clustering (see §10).
- **`OcrProcessor`** (`ml/ocr/`) — ML Kit Latin text recognition (English), run inline in indexing.
- **`ArabicOcrProcessor`** (`ml/ocr/`) — Tesseract wrapper; needs `assets/tessdata/ara.traineddata`
  and **disables itself gracefully** if the model is missing/init fails (indexing continues with
  English only). Grayscale preprocessing + confidence filtering.
- **`OrtAcceleration`** (`ml/`) — a **per-model, once-per-install benchmark** deciding CPU vs NNAPI:
  on first use of each model it times dummy inferences on both providers, keeps the faster, and
  caches the choice in SharedPreferences (`ort_acceleration_prefs`). Exists because NNAPI is a big
  win on some devices and a loss on others. Encoders close only their own session, never the shared
  `OrtEnvironment` singleton.

---

## 13. Concurrency, locking & priority

**No two background ML passes run at once.** Both `GalleryIndexerWorker` (indexing *or* Arabic) and
`FaceClusteringWorker` wrap their real work in the single process-wide
`GalleryService.mlExecutionLock` mutex. All three ML entry points — `indexImagesBackground`,
`runArabicOcrPass`, `createClusters` — are only ever called inside `withLock`. If a second worker is
dispatched while one holds the lock, it **suspends** on the lock rather than running concurrently.
`withLock` releases in a `finally`, so cancellation/exception can't leave a stuck lock; and the mutex
is in-memory, so a process kill can't leave it locked across restart.

**Search is intentionally *not* under this lock** — it uses the lightweight text encoder and must
stay responsive while a background pass runs (e.g. searching People during indexing).

**Priority: indexing > clustering > Arabic.**
- Clustering is **never preempted** — if it's running when indexing is triggered, indexing waits on
  the mutex.
- Arabic **is** preempted. Since an Arabic pass can be very long, a newly-triggered indexing must not
  wait it out. `startIndexingWorkManager` sets `GalleryService.indexingRequested = true`; the Arabic
  loop (and its pause wait-loop) polls this flag and, when set, **returns** — releasing the ML lock.
  Indexing then runs, clears the flag, chains to clustering, and clustering re-enqueues Arabic, which
  resumes from its `arabic_ocr_done` markers. Result: **every app-open and 6-hour trigger finishes
  index + cluster before Arabic continues.**

The three workers total: `GalleryIndexerWorker`, `FaceClusteringWorker` (both do locked ML work), and
`GalleryPeriodicTriggerWorker` (a trivial `Worker` that only enqueues indexing — no ML work).

---

## 14. Crash & shutdown safety

- **Indexing is fully resumable.** Writes are atomic batched transactions; on restart the
  device-vs-DB diff recomputes what's missing. WorkManager reschedules a killed worker (and pending
  work after reboot). Worst case after a kill: up to `INDEX_BATCH_SIZE` (10) in-flight images redone.
- **Incremental clustering is safe** — one atomic transaction; uncommitted work just leaves faces
  `personId == null` for next run.
- **Full/forced clustering is safe** — the wipe-and-rebuild is a single atomic transaction, so a
  crash during the (long) Chinese Whispers computation leaves the previous clustering intact; the
  `first_clustering_run_completed` flag is set only after commit. Custom names are lost only on a
  *forced* recluster (by design).
- **Arabic OCR is safe** — per-image `updateOcrText` + `markArabicOcrDone` in one transaction means
  no double-append and no misses across kills, pauses, or delete-then-re-add.

---

## 15. Notifications, pause & resume

The indexing/Arabic worker runs as a foreground service with a rich media-style notification built in
`GalleryIndexerWorker.createForegroundInfo`:
- Content text adapts to the pass via the `isArabicPass` flag: "Indexed X of Y photos" /
  "Scanned X of Y for Arabic text" (and the "Preparing…" variants).
- A **Pause** action fires `ACTION_PAUSE_INDEXING` → `GalleryIndexerReceiver` sets `isPaused = true`
  and swaps to the paused notification (`showPausedNotification`, also Arabic-aware).
- The paused notification's **Resume** action fires `ACTION_START_INDEXING` → the receiver clears
  `isPaused`, shows the resuming notification, and routes to the right worker: `startArabicOcrWorkManager()`
  if `isArabicPass`, else `startIndexingWorkManager(force = false)` (no-op if the worker is still
  alive and waiting; restarts it if it had been killed).

`FaceClusteringWorker` shows a plain indeterminate "Clustering faces…" notification with **no pause
action** — clustering isn't pausable.

---

## 16. Search subsystem

All in `GalleryService`, returning **`SearchResult(uris, relevantCount)`**:

- **Semantic (CLIP)** — `search(prompt, from, to)`: embed the prompt with the text encoder,
  dot-product against all media embeddings (served from the in-memory `getAllMediaCached`, so repeat
  searches don't re-deserialize every BLOB), sort by score. `@name` mentions
  (`findNamesInPrompt` + `PersonDao.getImagesByNames`) restrict to photos containing those people.
- **Relevance boundary** — `relevantCount` = how many leading results clear
  `SEARCH_RELEVANCE_THRESHOLD` (0.2). The grid draws a full-width "Less relevant" separator there.
  Non-scored searches use `SearchResult.all(...)` (relevantCount == size → no separator).
- **Document (OCR)** — `searchDocuments(text, …)`: normalizes the query; Arabic → `LIKE` path,
  otherwise FTS `MATCH` (`searchMediaFts`) with `LIKE` fallback.
- **Date-only** — merges images + videos from MediaStore by date.
- **Within a folder** — `searchWithin(mediaIds, …)`: applies prompt/date/sort to a restricted id set;
  used by all folder repositories via `FolderSource.getImagesFlow` (which therefore returns
  `Flow<SearchResult>`).

The Home search's live behavior is driven by `GalleryViewModel.isSearchActive` (a *committed*-search
flag) — **not** the live text box — so emptying the input doesn't reformat the still-displayed
results; only pressing Clear resets the view. (`SortMode` = `RELEVANCE` / `DATE_DESC`.)

---

## 17. The Folders abstraction (tabs 1–4)

- **`FolderSource`** — the interface all repos implement: `getFoldersFlow(): Flow<List<FolderItem>>`
  and `getImagesFlow(bucketId, prompt, useClip, from, to, sort): Flow<SearchResult>`.
- **`AlbumsFolderRepository`** — the only repo reading **raw MediaStore** (so albums mirror the
  phone's real folders live, incl. other apps' files). Reactive via a `ContentObserver` bridged into
  a `callbackFlow`; merges images + videos into the same buckets.
- **`PersonFolderRepository` / `CategoryFolderRepository` / `CollectionFolderRepository`** — Room-backed,
  reactive via Room `Flow`s; resolve their member media ids then hand off to `searchWithin` for
  in-folder search/filter.
- **`FolderItem`** — the folder-tile model (bucket id, name, count, up-to-4 thumbnail URIs); includes
  the `topFourThumbnails`/`padToFour` helper (top-4 by relevance, no duplication when < 4).
- **`PersonSort`** — the single source of truth for people ordering.

---

## 18. ViewModels

- **`DeletableViewModel`** (base) — multi-select state, bulk share, and the whole **delete flow**
  (see §21). All tab ViewModels extend it.
- **`FoldersViewModel`** (base for tabs 1–4) — folder-list flow, open-folder search state, favorites,
  scroll restoration; subclassed by `PeopleViewModel`, `CategoryViewModel`, `AlbumsViewModel`,
  `CollectionsViewModel` for their tab-specific actions.
- **`GalleryViewModel`** (Home) — the all-media flow + date-header timestamps, semantic/OCR/date
  search (`search`/`clearSearch`), `isSearchActive`, `relevantCount`, and the **Settings state**:
  `arabicOcrEnabled`/`updateArabicOcrEnabled`, `autoReclusterEnabled`/`updateAutoReclusterEnabled`,
  `themeMode`/`updateThemeMode` (all persisted in `gallery_prefs`).
- **`viewModels/factories/`** — `ViewModelProvider.Factory` boilerplate for each VM.

---

## 19. UI components

Home / grid:
- **`GalleryScreen`** — the Home screen: header + Settings gear, `SearchBar`, result-count label,
  the grid, and the empty state. Hosts the `SettingsDialog` and bulk-delete confirm dialog.
- **`ImageGrid` / `ImageGridScreen`** — the media grid: pinch-to-change column count, date headers
  ("Today"/"Yesterday"/date), the relevance separator, drag-select (long-press + per-cell tick
  haptics), and the **`MediaCell`** — which uses `BoxWithConstraints` to render a **video-duration
  badge** whose text/icon **scale with the tile size**.
- **`FullScreenImage`** — full-screen pager with zoom/pan, swipe-to-dismiss, and **video playback via
  Media3/ExoPlayer** (one player per video page, custom Compose control bar with seek/±10s/play-pause,
  buffering spinner, auto-hiding controls, haptics).
- **`SearchBar` / `SearchInputField` (@mention autocomplete) / `SearchModeToggle`** — the search UI.
- **`EmptyState`, `ShimmerGrid`** — premium placeholders (no photos / no matches; shimmer skeleton
  while loading).

Folders / dialogs:
- **`FoldersScreen`** — the generic folder grid + open-folder view wrapped by all four folder tabs.
- **`FolderTile`, `CreateButton`, `CustomDialog`, `CollectionPickerDialog`** and the per-tab folder
  screens (`PeopleFoldersScreen`, `CategoryFoldersScreen`, `AlbumsFoldersScreen`,
  `CollectionsFoldersScreen`).
- **`SettingsDialog`** — appearance (Light/Dark/System radios), auto-recluster switch, Arabic OCR
  switch; scrollable and capped at 80% of screen height.
- **`PermissionRequestScreen`** — first-run / denied-permission explainer.
- **`components/tutorial/`** — first-time per-tab overlay (see §23).

---

## 20. Settings & theming

**`ThemeMode`** enum (`ui/theme/`): `SYSTEM` (default), `LIGHT`, `DARK`. Persisted as its name under
`PREF_THEME_MODE`. `GalleryViewModel.themeMode` holds it; `MainActivity.setContent` maps it to
`darkTheme` (`SYSTEM → isSystemInDarkTheme()`, else fixed) and passes it to `GalleryTheme` — so a
change recomposes and re-themes live, no restart. Fresh installs follow the system; a user choice
persists.

**`GalleryTheme`** (`ui/theme/Theme.kt`) applies a custom light/dark `ColorScheme` (dynamic color is
intentionally off). `Color.kt`/`Type.kt` hold tokens; **`AppConfig.kt`** centralizes spacing, radii,
animation durations, and misc design constants.

Two other settings live in the same dialog: **auto re-group faces** (`PREF_AUTO_RECLUSTER`, default
on — gates the 300-face full recluster) and **Arabic text search** (`PREF_ARABIC_OCR`, default off —
enabling it kicks the pipeline so the library gets scanned).

---

## 21. Deletion flow

Implemented once in `DeletableViewModel`, shared by all tabs. Uses `MediaStore.createDeleteRequest`
(Android R+), which requires a system confirmation dialog surfaced via an `IntentSender` + a
monotonically increasing `intentSenderVersion` counter (so Compose reliably re-launches). Large
selections split into batches of 200 to avoid `TransactionTooLargeException`. Some ROMs return
`CANCELED` even on success, so it polls `isUriDeleted` before giving up. After device deletion,
`finalizeDeleteImages` cleans the DB (cascading faces, adjusting person counts, invalidating caches).

---

## 22. Caching

- **Search embedding cache** — `GalleryService.cachedMedia` holds all indexed media (with embeddings)
  in memory so repeat semantic searches don't re-read/deserialize every BLOB. Invalidated
  (`invalidateMediaCache()`) on every insert/delete so it never goes stale.
- **Thumbnail cache** — Coil's singleton `ImageLoader` (configured in `SmartGalleryApp`) with a 30%
  in-memory bitmap cache + its default disk cache. Grid cells use stable memory/disk cache keys and
  `placeholderMemoryCacheKey` so scrolled-away thumbnails repaint instantly (feels "stayed loaded").
- **ORT acceleration decision** — cached once per install per model in `ort_acceleration_prefs`.

---

## 23. Tutorial system

`components/tutorial/`. `Tutorial.kt` defines the `TutorialTab` enum (order matches pager pages) and
`tutorialContentFor(tab)` — the per-tab copy (icon, title, summary, checklist of tips). The Home tab's
tips include a line pointing at the Settings gear (theme, auto face re-grouping, Arabic search).
`TutorialPrefs` stores per-tab "seen" flags in a `tutorial_prefs` SharedPreferences file;
`resetAll()` re-arms every overlay. `TutorialOverlay.kt` is the modal card. `MainActivity.GalleryApp`
shows the overlay the first time the user lands on each tab (gated on permission), then marks it seen.

---

## 24. Utilities

- **`ImageUtils`** — MediaStore image scan, bitmap decode/downsample (long edge ~1024–2048 via
  `inSampleSize`), crop, thumbnail create/delete.
- **`VideoUtils`** — MediaStore video scan/date-filter, video URI helpers, `rememberVideoThumbnail`,
  and the new `rememberVideoDuration` + `formatVideoDuration` (for the duration badge).
- **`VectorUtils`** — vector math (dot product, normalize, add/subtract/divide, distance).
- **`ArabicTextNormalizer`** — Arabic normalization + `containsArabic`.

---

## 25. Complete file-by-file map

**Top level (`com.example.gallery`)**
- `SmartGalleryApp.kt` — Application; configures Coil's singleton ImageLoader.
- `MainActivity.kt` — single activity; hosts the 5-tab pager, owns all ViewModels, requests
  permissions & battery exemption, schedules periodic indexing, applies the theme.
- `GalleryService.kt` — central orchestrator singleton (indexing, search, clustering delegation,
  deletion, media cache, ML lock, work scheduling).
- `SortMode.kt` — `RELEVANCE` / `DATE_DESC` enum.
- `SearchResult.kt` — `SearchResult(uris, relevantCount)` + `all(...)`.

**`db/`**
- `AppDatabase.kt` — Room DB (v22), singleton builder, `MIGRATION_21_22`.
- `Converters.kt` — `FloatArray ⇆ ByteArray` embedding serialization.
- `GalleryIndexerWorker.kt` — foreground worker: indexing *or* Arabic OCR; pause/resume; throttled
  progress; `isPaused`/`isArabicPass` flags.
- `FaceClusteringWorker.kt` — foreground worker running clustering; chains Arabic if pending.
- `GalleryPeriodicTriggerWorker.kt` — 6-hour trigger (routes through `startIndexingWorkManager`).
- `GalleryIndexerReceiver.kt` — broadcast receiver for pause/resume notification actions.
- `db/daos/` — `MediaDao`, `PersonDao`, `CategoryDao`, `CollectionDao`, `FaceDao`, `DaoHelpers`.
- `db/entities/` — `MediaEntity`, `FtsMediaEntity`, `FaceEntity`, `PersonEntity`, `CategoryEntity`,
  `MediaCategoryCrossRef`, `CollectionEntity`, `CollectionMediaCrossRef`, `ArabicOcrDoneEntity`.
- `db/previews/` — `CategoryPreview`, `CollectionPreview`, `PersonPreview`, `PersonMediaRef`,
  `MediaDateInfo`, `MediaOcrRef`.

**`ml/`**
- `OrtAcceleration.kt` — CPU-vs-NNAPI per-model benchmark + cache.
- `ml/image/ClipImageEncoder.kt` — CLIP image tower.
- `ml/text/ClipTextEncoder.kt`, `ClipTokenizer.kt`, `TextEncoderProvider.kt` — CLIP text tower, BPE
  tokenizer, and the singleton provider.
- `ml/face/FaceEncoder.kt`, `FaceDetectionProcessor.kt`, `ChineseWhispers.kt`, `FaceClusterer.kt` —
  face embedding, detection/alignment, clustering algorithm, clustering orchestration.
- `ml/ocr/OcrProcessor.kt` (ML Kit Latin), `ArabicOcrProcessor.kt` (Tesseract).

**`folders/`**
- `FolderSource.kt` (interface), `AlbumsFolderRepository.kt` (MediaStore),
  `PersonFolderRepository.kt`, `CategoryFolderRepository.kt`, `CollectionFolderRepository.kt` (Room),
  `FolderItem.kt` (tile model + thumbnail helper), `PersonSort.kt` (people ordering).

**`viewModels/`**
- `DeletableViewModel.kt` (base: select/share/delete), `FoldersViewModel.kt` (base for tabs 1–4),
  `GalleryViewModel.kt` (Home + settings state), `PeopleViewModel.kt`, `CategoryViewModel.kt`,
  `AlbumsViewModel.kt`, `CollectionsViewModel.kt`, and `viewModels/factories/` (5 factories).

**`components/`**
- Home/grid: `GalleryScreen.kt`, `ImageGridScreen.kt`, `ImageGrid.kt`, `FullScreenImage.kt`,
  `EmptyState.kt`, `ShimmerGrid.kt`.
- Search: `SearchBar.kt`, `SearchInputField.kt`, `SearchModeToggle.kt`.
- Folders/dialogs: `FoldersScreen.kt`, `FolderTile.kt`, `CreateButton.kt`, `CustomDialog.kt`,
  `CollectionPickerDialog.kt`, `SettingsDialog.kt`, `PermissionRequestScreen.kt`, and the four per-tab
  folder screens (`PeopleFoldersScreen.kt`, `CategoryFoldersScreen.kt`, `AlbumsFoldersScreen.kt`,
  `CollectionsFoldersScreen.kt`).
- `components/tutorial/` — `Tutorial.kt`, `TutorialOverlay.kt`.

**`ui/theme/`**
- `Theme.kt` (GalleryTheme), `Color.kt`, `Type.kt`, `AppConfig.kt` (design tokens), `ThemeMode.kt`.

**`utils/`**
- `ImageUtils.kt`, `VideoUtils.kt`, `VectorUtils.kt`, `ArabicTextNormalizer.kt`.

**Tests**
- `test/` (JVM): `VectorUtilsTest`, `ChineseWhispersTest`, `db/ConvertersTest`.
- `androidTest/` (instrumented Room): `MediaDaoTest`, `PersonDaoTest`, `FaceAndClusteringTest`,
  `OcrProcessorTest`.

---

## 26. Cross-cutting invariants

Things that must stay true; breaking any of these is a real bug:

1. **Single instances** — `GalleryService`, `AppDatabase`, and `TextEncoderProvider`'s encoder are
   one-per-process. All ML/lock/cache state lives in `GalleryService`'s companion.
2. **One background ML pass at a time** — everything heavy goes through `mlExecutionLock`.
3. **Pipeline order** — index → cluster → Arabic, always, each chained on success.
4. **Indexing is a diff** — never a from-scratch reprocess; only new photos do ML work.
5. **Embedding byte layout is fixed** — `Converters` and `PersonDao.updateEmbedding` must agree
   (guarded by `ConvertersTest`).
6. **Atomic commits** — batched indexing writes, the full-recluster wipe-and-rebuild, and each Arabic
   text+marker write are single transactions, so any kill leaves a consistent DB.
7. **Category list is re-queried per image** during indexing (live folder updates) — do not hoist it.
8. **Search runs outside the ML lock** — it must stay responsive during background work.
9. **Arabic yields to indexing; clustering never does.**

---

## 27. Known limitations & gotchas

- `MediaEntity.isVideo` is vestigial (always false) — videos are not indexed into Room; they come
  straight from MediaStore.
- Room still declares `fallbackToDestructiveMigration(false)` with real migrations now added — but
  every future schema/version bump needs a written `Migration` or the build will fail (no silent
  destructive fallback).
- Arabic OCR reads the *shared* ~1024–2048px decode; it's tuned for correctness/robustness, not
  maximum recognition accuracy. Higher-res decode or a text-detection-first pass would improve
  accuracy at a speed cost (deliberately not done).
- `FaceClusterer` imports `GalleryService` (for the `PREF_AUTO_RECLUSTER` key), so the two reference
  each other — compiles fine, but if you want `ml.face` fully decoupled, move the pref keys to a
  neutral constants holder.
- `ImageUtils` and `ClipTokenizer` have no dedicated tests (both need instrumented tests for
  Bitmap/asset access).
```
