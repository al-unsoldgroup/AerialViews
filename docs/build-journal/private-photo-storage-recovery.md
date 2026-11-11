# Recoverable private photo storage: R2 evaluation

Status: an integration is implemented locally behind a feature flag, but is not deployed or verified with real Google media. The entries below record the investigation in chronological order. The existing Krill library uses Cloudflare Images; do not move or delete those images as part of this experiment.

## Why investigate this

Cloudflare Images generates a private draft ID during Direct Creator Upload. Its documentation says unuploaded drafts do not appear in image listings. If the creation response is lost before Krill saves the ID, the current importer cannot reliably recover the draft. Cloudflare R2 allows an application to choose its object key, and its Worker binding can feed private bytes into the existing Images transformation binding. This could keep Cloudflare's automatic focus and sizing while giving Krill an identity it already knows before writing bytes.

## Proposed shape

1. Give each import operation a random, non-guessable R2 key in a dedicated private bucket. Persist the key, owner, expected content hash and job state before attempting the write. Never derive a publicly readable path from a filename or expose a Google URL or credential.
2. Write with an atomic create-only condition. After an uncertain response, inspect the same key and verify its size, hash and operation ownership before committing the media record. An empty read alone is not proof that an earlier write cannot still finish.
3. Store the backend and object key explicitly in Krill's media record. Frame and gallery endpoints must authorize the requesting space before reading the bucket; pass its stream to the existing Images binding for screen-sized automatic crops. Existing Cloudflare Images-backed media must continue to work.
4. Fence concurrent writers and deletion with durable operation state. A delete must prevent an older in-flight upload from recreating the object after the deletion is reported complete. Confirm absence through the R2 binding only after the write has been definitively settled, then record completion and invalidate device copies.
5. Keep transformed responses private. Images binding responses are not automatically cached; design a cache key, authorization boundary, invalidation and purge behavior before claiming seamless cached delivery. Do not put private images into a shared cache merely to improve performance.

## Validation needed

- Demonstrate conditional writes and recovery from a lost response, including an in-flight write that finishes after a negative read.
- Exercise deletion racing a pending upload, retries, revoked sessions, two spaces, and an offline TV reconnecting after removal.
- Verify private R2-to-Images transformations at 1080p and 4K on representative photos, including automatic focus, portrait layouts, source EXIF and no enlargement.
- Confirm the cost, bucket configuration and lifecycle with a bounded synthetic test. The binding and cache require separate operational configuration.
- Update all Krill read, thumbnail, deletion and export paths before importing any real media into the new backend. Review and test a mixed library and rollback. Do not enable until this is complete.

## Initial synthetic test

A remote Worker used an isolated, random test key in an existing private R2 bucket. A create-only write succeeded, a second create-only write was rejected, the original synthetic bytes remained readable, and a read after deletion reported the object absent. The test removed its key and stopped the preview. This checks the basic binding behavior for one object; it does not test an uncertain in-flight write, the photo importer, access controls, transformations, caching or a TV.

A second synthetic test held a write open, observed the key absent, deleted it, and then allowed the write to finish. The object appeared after the delete, confirming that a negative read and delete alone cannot fence a pending write. In a separate test, a create-only tombstone was committed before the held write supplied its bytes; the late create-only write was rejected and the tombstone remained. Both tests removed their synthetic keys. This demonstrates one ordering only. A complete design must also handle an already committed object, competing tombstones, uncertain responses and when it is safe to remove the tombstone.

A further remote test conditionally replaced an already committed synthetic object with a tombstone, rejected a late create-only write, and verified the stored SHA-256 checksum before cleanup. A local storage primitive now implements create-only writes, checksum verification and retained tombstones, with focused tests. It is not connected to Krill's import, authorization, media delivery or deletion paths. Tombstone lifetime, concurrent retries, cache invalidation and integration still need review and verification.

Additional local tests simulate a lost response after a write or tombstone has committed, and two concurrent tombstone attempts. Retrying preserves the tombstone and blocks a later create-only write. These are simulated tests, not proof of the full end-to-end deletion protocol.

The local server now has a migration to distinguish existing Cloudflare-backed media from R2 photos and a feature-gated frame image route for R2. The route checks library membership before reading, verifies the object's operation marker and photo state, and returns private no-store responses. Focused access and storage tests and TypeScript checks passed. No bucket is configured and no import, gallery or deletion path has been switched to R2; the migration and route have not been deployed.

The local gallery proxy also has a feature-gated R2 read path. It checks the media's space against a validated session before accessing the bucket and does not send these objects through the public CDN cache. Tests cover authorized access, an unrelated space and an unauthenticated visitor. It currently returns the original for thumbnail requests; appropriately sized private variants and cache behavior still need implementation and review. The route is not deployed.

The local frame deletion journal now records the photo's storage backend and uses an R2 tombstone for R2-backed photos. A SQLite-backed test simulates a lost tombstone response and verifies that retry completes the journal without calling the Images deletion API. Focused tests and TypeScript checks passed. This does not yet cover deletion through the general Krill interface, concurrent edits, cache invalidation or a real TV; the changes remain disabled and undeployed.

The local single and bulk Krill deletion routes now also require a successful R2 tombstone before removing an R2 photo's database record. Tests cover both routes and an uncertain tombstone response. These paths still need concurrency review, device-cache reconciliation and end-to-end testing; no real R2 photos have been imported.

The local Google Picker photo importer can now select R2 for new jobs behind a feature flag. It records the operation key and source hash before writing, checks the stored object's checksum, and commits the library record only afterward. A simulated lost write response resumes using the same key. An expired selection can transfer an R2 job to a later selection for the same Google account and item without changing its backend. Focused tests and TypeScript checks passed. This path is not configured, deployed, independently reviewed or tested with Google media; existing Cloudflare Images jobs remain on their original backend.

An independent review found that duplicate and MCP deletion bypassed R2, and that abandoned Picker jobs could leave an unreferenced object. Local fixes route those deletion paths through backend-aware storage handling and add a bounded sweep that fences expired, unleased jobs with tombstones. A database trigger removes completed Picker metadata when its media row is deleted. The local R2 gallery now requests sized JPEG variants, and the frame converts HEIC to JPEG when needed. Focused tests and TypeScript checks passed. These changes still require independent re-review and real-media, cache and device verification.

An independent re-review cleared those three storage lifecycle blockers, with 58 focused tests passing. The reviewer also identified two recovery issues: one failed cleanup task could prevent subsequent cleanup tasks, and a failed Picker job could return to pending after an unsuccessful sweep. Local changes address both; six focused recovery tests passed on the VPS. Migrations 043 and 044 must precede any Worker deployment, and all required migrations must be applied in order. The R2 feature remains disabled until the remaining media paths and end-to-end behavior are verified.

A subsequent source audit found additional paths that still construct Cloudflare Images URLs: image downloads, WebDAV, AI tagging, collection cover selection and MCP media URLs. An R2 photo has no matching Images object, so these paths cannot yet be assumed to work for it. Each needs backend-aware handling and appropriate authorization, or an explicit supported limitation, before the feature flag can be enabled.

Local changes now cover downloads, WebDAV, AI tagging, AI cover selection and authenticated MCP image retrieval. Focused tests exercise these paths with synthetic data. While auditing them, we found that public gallery and tag metadata can expose private photo details even when the image bytes are protected. Initial filters have been added to the main media and public collection queries, but public tag and collection endpoints and non-R2 private imports still need a comprehensive privacy audit. Do not enable the feature on a publicly visible library until that audit and independent review are complete.

Cloudflare documents [R2 consistency and conditional writes](https://developers.cloudflare.com/r2/reference/consistency/) and [Images transformations from private streams](https://developers.cloudflare.com/images/optimization/binding/). The proposal is an inference from those contracts, not an end-to-end verified implementation.
