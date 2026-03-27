#!/bin/bash

# Usage: ./scripts/release.sh v1.1.0

set -e

TAG="$1"

if [ -z "$TAG" ]; then
    echo "Usage: ./scripts/release.sh <version-tag>"
    echo "Example: ./scripts/release.sh v1.1.0"
    exit 1
fi

if ! echo "$TAG" | grep -qE '^v[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "ERROR: Tag must be in format vX.Y.Z (e.g. v1.1.0)"
    exit 1
fi

if git tag -l | grep -q "^${TAG}$"; then
    echo "ERROR: Tag $TAG already exists"
    exit 1
fi

# Strip 'v' prefix for pubspec version (v1.2.3 -> 1.2.3)
VERSION="${TAG#v}"

# Update ref in README.md
sed -i '' "s/ref: v[0-9]*\.[0-9]*\.[0-9]*/ref: $TAG/" README.md

# Update version in pubspec.yaml
sed -i '' "s/^version: .*/version: $VERSION/" pubspec.yaml

echo "Updated README.md ref to $TAG and pubspec.yaml version to $VERSION"

# Commit and tag
git add README.md pubspec.yaml
git commit -m "Release $TAG"
git tag "$TAG"

# Push commit and tag
git push
git push origin "$TAG"

echo ""
echo "Done! Released $TAG (commit and tag pushed)."
