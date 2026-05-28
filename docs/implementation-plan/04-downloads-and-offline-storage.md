# 04 Downloads And Offline Storage

## Goal

Implement reliable full-book downloads that support offline playback without needing fresh server data.

## Scope

- Fetch `GET /api/books/:id` for a selected book.
- Download all chapter audio and optional cover art.
- Persist local metadata using stable IDs.
- Support deleting downloaded books.
- Clear all downloads on logout.

## Product Rules

- Full downloads only in v1.
- Only downloaded books are playable.
- If a presigned download URL fails, surface an error for now.
- Presigned URLs must not be used as durable identity.
- Local metadata should be keyed by stable IDs such as `book.id`, `chapter.id`, and `media_asset_id`.

## Deliverables

- Download button/state in library.
- Single-book download pipeline.
- Download status and failure UI.
- Durable local representation of:
  - book detail
  - chapters
  - local file paths
  - local progress
  - cover file path
- Delete download action.

## Recommended Storage Model

- Room stores metadata and progress.
- App-private files directory stores media files.
- Book directory structure should be based on stable IDs, for example:
  - `books/<bookId>/cover.*`
  - `books/<bookId>/chapters/<chapterId>.<ext>`
- If audio extension is not trustworthy, preserve content type or use server path extension only as a convenience.

## Suggested Tasks

1. Define local database entities for downloaded books and chapters.
2. Define file layout for downloaded assets.
3. Implement `GET /api/books/:id`.
4. Implement a single-book download use case with progress reporting.
5. Persist metadata only after all required files succeed.
6. Surface download failure and allow retry.
7. Implement delete download.
8. Implement full local cleanup on logout.

## Edge Cases

- Cover download succeeds but a chapter download fails.
- App dies during download.
- User taps download twice.
- Presigned URL expires mid-transfer.
- Server detail payload changes chapter order.

## Definition Of Done

- A user can download a full book, close the app, reopen it offline, and still see that book as playable.