# Exec plan: Auth UI polish and share-by-file/folder

- **Status**: active
- **Goal**: Close the two milestone gaps still open per `README.md`'s
  milestone table — M5 (auth UI polish) — plus the sharing granularity item
  noted during M2–M4 development, carried over from the plugin's former
  ad-hoc TODO notes (now removed).

## Acceptance criteria

- [ ] Hosting/joining a session offers a provider chooser dialog when more
      than one auth provider is configured (M5).
- [ ] Form-based auth (username/password-style providers) has a working
      SWT dialog wired to `AuthenticationService` (M5).
- [ ] A provider that requires a login page (OAuth-style) opens it in an SWT
      `Browser` and completes the flow (M5).
- [ ] A host can share a single file or a subfolder, not only the whole
      workspace project.

## Decomposition

- [ ] Design the provider chooser dialog (list providers from `InitData`/
      `AuthMetadata`, launch the right flow per provider type).
- [ ] Wire `FormAuthDialog` end to end against `AuthenticationService`.
- [ ] Wire `LoginBrowserDialog` end to end for browser-flow providers.
- [ ] Decide the sharing-granularity UX (single file vs. folder vs. whole
      project) and extend `HostSessionHandler` / `WorkspaceFileSystemService`
      accordingly.

## Progress log

- 2026-09-11: Plan created, carrying forward the open items from the
  plugin's former ad-hoc TODO notes (now removed — its bug postmortems were
  merged into sync-and-save-lifecycle.md, its test-project note is already
  satisfied by `org.eclipse.oct.tests`).

## Open questions

- Which auth providers does M5 need to support at launch (affects how much
  the chooser dialog needs to generalize)?
- Is "share by file/folder" scoped to the host UI only, or does the guest-side
  EFS/`OctFileSystem` need changes too?
