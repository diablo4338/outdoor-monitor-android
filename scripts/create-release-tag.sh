#!/usr/bin/env bash
set -euo pipefail

version="${1:-}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Usage: bash scripts/create-release-tag.sh MAJOR.MINOR.PATCH" >&2
  exit 2
fi

git fetch --tags origin

tag="app-v$version"
if git rev-parse --verify --quiet "refs/tags/$tag" >/dev/null; then
  echo "Tag already exists: $tag" >&2
  exit 1
fi

latest_tag="$(git tag --list 'app-v*' | sort -V | tail -n1)"
if [[ -n "$latest_tag" ]]; then
  latest_version="${latest_tag#app-v}"
  highest="$(printf '%s\n%s\n' "$latest_version" "$version" | sort -V | tail -n1)"
  if [[ "$version" == "$latest_version" || "$highest" != "$version" ]]; then
    echo "Tag downgrade rejected: $version is not newer than $latest_version" >&2
    exit 1
  fi
fi

git tag -a "$tag" -m "Android release base $version"
echo "Created $tag at $(git rev-parse --short HEAD)"
echo "Publish it with: git push origin $tag"
