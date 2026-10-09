## Ideas

- [x] When openening an editor as a guest we should check if there is a default eclipse editor registered for this file/extension, otherwise a system editor will pop-up. But it can't read oct:// files. If no editor registered, open with default eclipse text editor.

- [ ] Would be nice to have a context menu entry in package/project explorer view for quick share of files. Right now we can only share the whole project

- [ ] In SessionView File column show also line/column numbers is easy possible.

- [ ] Add decorator to project explorer marking files currently viewing by participants. If inside the colapsed folder, the folder should also indicate that it contains files being viewed. It can be turned of in the preferences. Also the sessions view toolbar menu should have an entry to toggle this feature.

- [x] Add well-known default ignores (e.g. `bin`, `target`, `.DS_Store`, `node_modules`) so these are excluded from both sharing and local change observation. Add a preference that is on by default.

- [ ] Add decorator to project explorer marking files currently being shared. If inside the colapsed folder, the folder should also indicate that it contains files being shared. It can be turned of in the preferences. Also the sessions view toolbar menu should have an entry to toggle this feature.

- [ ] Add a possibility to ignore files from share using `.gitignore` semantics (incl. nested `.gitignore` files and negation patterns) or a user-defined list of patterns. Consider reusing EGit/JGit's `IgnoreNode` instead of a hand-rolled parser. Or even better ask org.eclipse.team for TeamProvider service is it works. Doing so we also cover SVN and other version control systems.

## Check - todo

- [ ] Check that images cretaed in the session view are properly disposed, cached. Images cost OS handles.
