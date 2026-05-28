# 03 Library And Images

## Goal

Show the online Sonnet library in a Compose-first UI while reflecting local downloaded state inline and caching covers sensibly.

## Scope

- Fetch `GET /api/books`.
- Merge server books with local downloaded metadata.
- Surface download state in the main library list.
- Open the player route when a book is tapped.
- Only allow playback if the book is downloaded.
- Cache cover art in a way that avoids churn from expiring presigned URLs.

## Deliverables

- Library screen with pull-to-refresh.
- Rows/cards that show:
  - title
  - author
  - narrator if present
  - completion status
  - downloaded state
  - download in progress or failure state
- Book tap opens the player screen for that book.
- If a book is not downloaded, player entry should present a clear non-playable state or route through download affordance.

## Key Product Rules

- There is no separate downloaded tab in v1.
- Downloaded books remain visible in the main library.
- The app should still be able to render downloaded metadata offline if the online library is unavailable.
- Image caching should prefer a stable key if practical, such as `book.id` or a canonicalized path-based identity, rather than full presigned URL.

## Suggested Technical Shape

- Library repository should merge:
  - remote `BookSummary`
  - local download status
  - local progress metadata
- The UI model should have explicit fields for:
  - `isDownloaded`
  - `downloadStatus`
  - `localCoverUri`
  - `remoteCoverUrl`
  - `isCompleted`

## Suggested Tasks

1. Define local and remote book models.
2. Implement `GET /api/books`.
3. Create library repository that merges remote and local state.
4. Build library screen and row/card composables.
5. Add pull-to-refresh and empty/offline states.
6. Add image loading and caching strategy.
7. Add library-to-player navigation.

## Edge Cases

- Online library request fails but local downloaded books exist.
- Cover URL changes because a new presigned URL was issued.
- A locally downloaded book is no longer present in the current server library response.

## Definition Of Done

- The main library is usable online and still meaningful offline.
- Downloaded state is obvious.
- Cover caching does not thrash due to expiring URL query strings.