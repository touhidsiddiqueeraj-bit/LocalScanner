#!/usr/bin/env bash
# LocalScanner - push main + re-tag v1.0.0 so GitHub Actions rebuilds the release.
#
# WHY: tag v1.0.0 exists on origin already (pushed this morning from an
# incomplete commit, ae548d1). It was deleted locally and re-created on the
# fixed commit (c7ddb8c). Pushing over an existing tag is rejected, so the
# stale remote tag + its GitHub Release must be removed first.
#
# Usage:  ./push_and_release.sh          (run from anywhere; cd's to repo)
#
# Requirements: git with push access to
#   https://github.com/touhidsiddiqueeraj-bit/LocalScanner
# (gh CLI is used when available to drop the stale Release; otherwise the
# script prints the two clicks needed in the browser.)

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_DIR"

TAG="v1.0.0"
BRANCH="main"
REMOTE="origin"

echo "==> Repo: $REPO_DIR"
echo "==> HEAD: $(git rev-parse --short HEAD) $(git log -1 --pretty=%s)"
echo "==> Tag : $(git rev-parse --short "$TAG" 2>/dev/null || echo MISSING) -> ${TAG}"
if [ "$(git rev-parse --short "$TAG^{commit}")" != "$(git rev-parse --short HEAD)" ]; then
  echo "!! Tag does not point at HEAD - re-tag first:  git tag -f $TAG" >&2
  exit 1
fi

# 1. Drop the stale remote tag (old commit) -------------------------------
echo "==> Removing stale remote tag ${TAG} ..."
git push "$REMOTE" --delete "refs/tags/${TAG}" 2>/dev/null \
  || echo "   (tag already absent on ${REMOTE} - continuing)"

# 2. Drop the stale GitHub Release (optional but recommended) -------------
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  echo "==> Deleting stale GitHub Release ${TAG} (gh) ..."
  gh release delete "$TAG" --repo touhidsiddiqueeraj-bit/LocalScanner --yes \
    || echo "   (release not found - continuing)"
else
  echo "!! gh CLI not authenticated. Please delete the old release manually:"
  echo "   https://github.com/touhidsiddiqueeraj-bit/LocalScanner/releases"
  echo "   -> v1.0.0 -> Delete (keeps history clean before re-pushing the tag)"
  read -r -p "   Press Enter once the old release is deleted (or Ctrl+C to abort)... "
fi

# 3. Push main and the fixed tag ------------------------------------------
echo "==> Pushing ${BRANCH} ..."
git push "$REMOTE" "$BRANCH"

echo "==> Pushing ${TAG} (triggers release.yml: signed APK + GitHub Release) ..."
git push "$REMOTE" "refs/tags/${TAG}"

echo
echo "Done. Watch the build:"
echo "  https://github.com/touhidsiddiqueeraj-bit/LocalScanner/actions"
echo "Release (auto-created with the APK in ~6-8 min):"
echo "  https://github.com/touhidsiddiqueeraj-bit/LocalScanner/releases/tag/${TAG}"
