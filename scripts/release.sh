#!/usr/bin/env bash
# Release Better Monster Examine to the RuneLite plugin hub.
#
# Releases are their own line of history: each one is built on the previous release tag, never on
# main, and never merges back. main carries no version= line; the release commit adds it. The
# default release is everything on main not yet released (plugin-hub reviewers asked for small
# changes to come batched); naming commits holds the rest back.
#
# Everything up to the [y/N] prompt is local. After it: push the tag, create the GitHub release,
# sync the plugin-hub fork, and open or update the hub PR from it.

set -euo pipefail

REPO=prettyrocket/better-monster-examine
HUB=runelite/plugin-hub
FORK=prettyrocket/plugin-hub
HUB_BRANCH=better-monster-examine-release
MANIFEST=plugins/better-monster-examine
PROPS=runelite-plugin.properties
NAME="Better Monster Examine"

usage() {
	cat <<'EOF'
Usage: scripts/release.sh [options] [commit...]

Builds a release on the last v* tag from the named main commits (default: every unreleased
commit on origin/main), stamps version= in runelite-plugin.properties, runs ./gradlew build,
shows the plan and asks before pushing anything.

Options:
  --minor            bump the minor version (default: patch)
  --major            bump the major version
  --version X.Y.Z    release as exactly this version
  --title TEXT       release subtitle: "Better Monster Examine vX.Y.Z — TEXT"
  --notes-file FILE  release notes (Markdown); default lists the released commits
  --redo             replace the latest release (its tag and GitHub release are deleted) while
                     its hub PR is still unmerged; keeps its version, title and notes by default
  --dry-run          build and show the plan, then stop before pushing anything
  -h, --help         show this help
EOF
}

die() { echo "release: $*" >&2; exit 1; }
warn() { echo "warning: $*" >&2; }

bump=patch
explicit=""
title=""
notes_file=""
redo=false
dry_run=false
picks=()

while [ $# -gt 0 ]; do
	case "$1" in
		--minor) bump=minor ;;
		--major) bump=major ;;
		--version) explicit="${2:?--version needs X.Y.Z}"; shift ;;
		--title) title="${2:?--title needs text}"; shift ;;
		--notes-file) notes_file="${2:?--notes-file needs a path}"; shift ;;
		--redo) redo=true ;;
		--dry-run) dry_run=true ;;
		-h|--help) usage; exit 0 ;;
		-*) die "unknown option $1 (see --help)" ;;
		*) picks+=("$1") ;;
	esac
	shift
done

if [ -n "$explicit" ] && ! [[ "$explicit" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
	die "--version must be X.Y.Z, got '$explicit'"
fi
if [ -n "$notes_file" ] && [ ! -f "$notes_file" ]; then
	die "no notes file at $notes_file"
fi
[ -n "$notes_file" ] && notes_file="$(cd "$(dirname "$notes_file")" && pwd)/$(basename "$notes_file")"

cd "$(git rev-parse --show-toplevel)"
gh auth status >/dev/null 2>&1 || die "gh is not logged in"

# A version as three space-separated numbers; v1.10 counts as 1.10.0.
parse() {
	local v="${1#v}" a b c
	IFS=. read -r a b c <<<"$v"
	echo "$a ${b:-0} ${c:-0}"
}

# Succeeds when version $1 is greater than version $2.
greater() {
	local a1 b1 c1 a2 b2 c2
	read -r a1 b1 c1 <<<"$(parse "$1")"
	read -r a2 b2 c2 <<<"$(parse "$2")"
	[ "$a1" -ne "$a2" ] && { [ "$a1" -gt "$a2" ]; return; }
	[ "$b1" -ne "$b2" ] && { [ "$b1" -gt "$b2" ]; return; }
	[ "$c1" -gt "$c2" ]
}

echo "Fetching origin..."
git fetch --quiet --tags --force origin main

release_tags() {
	git tag -l 'v*' --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+(\.[0-9]+)?$' || true
}

redo_tag=""
if $redo; then
	redo_tag="$(release_tags | sed -n 1p)"
	last="$(release_tags | sed -n 2p)"
	[ -n "$redo_tag" ] && [ -n "$last" ] || die "--redo needs at least two release tags"
else
	last="$(release_tags | sed -n 1p)"
	[ -n "$last" ] || die "no v* release tag to build on"
fi

# Which commit the hub serves today.
hub_pinned="$(gh api "repos/$HUB/contents/$MANIFEST" --jq .content | base64 -d | sed -n 's/^commit=//p' | tr -d '\r')"

if $redo; then
	redo_sha="$(git rev-parse "$redo_tag^{commit}")"
	[ "$hub_pinned" != "$redo_sha" ] || die "$redo_tag is already live on the hub; release a new version instead of --redo"
fi

# Version.
read -r ma mi pa <<<"$(parse "$last")"
case "$bump" in
	patch) next="$ma.$mi.$((pa + 1))" ;;
	minor) next="$ma.$((mi + 1)).0" ;;
	major) next="$((ma + 1)).0.0" ;;
esac
if [ -n "$explicit" ]; then
	version="$explicit"
	greater "$version" "${last#v}" || die "$version is not greater than the last release ($last)"
	case "$version" in
		"$ma.$mi.$((pa + 1))"|"$ma.$((mi + 1)).0"|"$((ma + 1)).0.0") ;;
		*) warn "$version skips numbers after $last" ;;
	esac
elif $redo && [ "$bump" = patch ]; then
	version="$(parse "$redo_tag" | tr ' ' .)"
else
	version="$next"
fi
tag="v$version"
if [ "$tag" != "$redo_tag" ]; then
	git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "tag $tag already exists"
	gh release view "$tag" -R "$REPO" >/dev/null 2>&1 && die "a GitHub release $tag already exists"
fi

# Commits. git cherry matches by patch id, so main commits already cherry-picked onto the release
# line count as released even though their hashes differ.
mapfile -t unreleased < <(git cherry "$last" origin/main | sed -n 's/^+ //p')
[ ${#unreleased[@]} -gt 0 ] || die "nothing on origin/main is unreleased since $last"

commits=()
if [ ${#picks[@]} -eq 0 ]; then
	commits=("${unreleased[@]}")
else
	declare -A wanted=()
	for p in "${picks[@]}"; do
		sha="$(git rev-parse -q --verify "$p^{commit}")" || die "no such commit: $p"
		printf '%s\n' "${unreleased[@]}" | grep -qx "$sha" || die "$p is not an unreleased commit on origin/main"
		wanted[$sha]=1
	done
	# Main's order, whatever order they were named in.
	for sha in "${unreleased[@]}"; do
		[ -n "${wanted[$sha]:-}" ] && commits+=("$sha")
	done
fi
held=$(( ${#unreleased[@]} - ${#commits[@]} ))

# Build the release in a throwaway worktree, so this checkout is never touched.
wt="$(mktemp -d)"
cleanup() { git worktree remove --force "$wt" >/dev/null 2>&1 || rm -rf "$wt"; }
trap cleanup EXIT
git worktree add --quiet --detach "$wt" "$last"

for sha in "${commits[@]}"; do
	if ! git -C "$wt" cherry-pick "$sha" >/dev/null; then
		git -C "$wt" cherry-pick --abort || true
		die "$(git log -1 --format='%h %s' "$sha") does not apply on $last; nothing was pushed"
	fi
done

# The release workflow this script replaces still lives on old release tags. A release whose tree
# keeps it would run it on publish, so the commit that deletes it has to ship first.
if [ -e "$wt/.github/workflows/release.yml" ]; then
	die "this release would still contain .github/workflows/release.yml; include the commit that removes it"
fi

# Write the file whole with LF endings: appending in a CRLF checkout leaves mixed endings.
{ git -C "$wt" show "HEAD:$PROPS" | tr -d '\r' | grep -v '^version='; echo "version=$version"; } >"$wt/$PROPS.new"
mv "$wt/$PROPS.new" "$wt/$PROPS"
git -C "$wt" add "$PROPS" 2>/dev/null
git -C "$wt" commit --quiet -m "Release $version"
release_sha="$(git -C "$wt" rev-parse HEAD)"

committed="$(git -C "$wt" cat-file -p "HEAD:$PROPS")"
if printf '%s' "$committed" | grep -q $'\r' \
	|| [ "$(printf '%s\n' "$committed" | grep -c '^version=')" -ne 1 ] \
	|| [ "$(printf '%s\n' "$committed" | tail -n 1)" != "version=$version" ]; then
	die "the committed $PROPS is malformed; inspect $release_sha"
fi

echo "Building $tag ($release_sha)..."
build_log="$(mktemp)"
if (cd "$wt" && ./gradlew build --console=plain) >"$build_log" 2>&1; then
	build="passed"
else
	tail -n 40 "$build_log" >&2
	die "./gradlew build failed on $release_sha (full log: $build_log); nothing was pushed"
fi
rm -f "$build_log"

# Notes. A redo keeps the replaced release's title and notes when the version is unchanged.
notes="$(mktemp)"
trap 'cleanup; rm -f "$notes"' EXIT
release_name="$NAME $tag${title:+ — $title}"
if [ -n "$notes_file" ]; then
	tr -d '\r' <"$notes_file" >"$notes"
elif $redo && [ "$tag" = "$redo_tag" ]; then
	gh release view "$redo_tag" -R "$REPO" --json body --jq .body >"$notes"
	[ -n "$title" ] || release_name="$(gh release view "$redo_tag" -R "$REPO" --json name --jq .name)"
else
	for sha in "${commits[@]}"; do
		echo "- $(git log -1 --format=%s "$sha")"
	done >"$notes"
fi
if ! grep -q "/compare/" "$notes"; then
	printf '\n**Full changelog:** https://github.com/%s/compare/%s...%s\n' "$REPO" "$last" "$tag" >>"$notes"
fi

open_pr="$(gh pr list -R "$HUB" --author "${FORK%%/*}" --head "$HUB_BRANCH" --state open --json number,title \
	--jq '.[0] | select(.) | "#\(.number) (\(.title))"')"

hub_live="$(git tag --points-at "$hub_pinned" 2>/dev/null | head -n 1)"

echo
echo "================ Release plan ================"
echo "Version:   $tag  (last release $last)"
echo "Name:      $release_name"
if $redo; then
	echo "Replaces:  $redo_tag ($redo_sha); its tag and GitHub release get deleted"
fi
echo "Commit:    $release_sha"
echo "Commits:   ${#commits[@]} from origin/main$( [ "$held" -gt 0 ] && echo ", $held held back")"
for sha in "${commits[@]}"; do
	echo "           $(git log -1 --format='%h %s' "$sha")"
done
if [ "$held" -gt 0 ]; then
	echo "Held back:"
	for sha in "${unreleased[@]}"; do
		printf '%s\n' "${commits[@]}" | grep -qx "$sha" || echo "           $(git log -1 --format='%h %s' "$sha")"
	done
fi
echo "Build:     $build"
echo "Hub:       serves ${hub_live:-$hub_pinned}; will ${open_pr:+update open PR }${open_pr:-open a new PR}"
echo "Notes:"
sed 's/^/  | /' "$notes"
echo "=============================================="

if $dry_run; then
	echo "Dry run: nothing pushed. The release commit stays inspectable as $release_sha until git gc."
	exit 0
fi

read -r -p "Push $tag, create the release and update the hub PR? [y/N] " answer </dev/tty
case "$answer" in
	y|Y|yes|YES) ;;
	*) echo "Aborted; nothing pushed."; exit 1 ;;
esac

if $redo; then
	echo "Deleting $redo_tag..."
	gh release delete "$redo_tag" -R "$REPO" --yes --cleanup-tag
	git tag -d "$redo_tag" >/dev/null
fi

echo "Pushing $tag..."
git tag "$tag" "$release_sha"
git push --quiet origin "refs/tags/$tag"

release_url="$(gh release create "$tag" -R "$REPO" --verify-tag --title "$release_name" --notes-file "$notes")"
echo "Release: $release_url"

# Build the hub branch as a single commit on the fork's freshly synced master, via the API, so
# there's no plugin-hub clone. Any upstream commit the fork lacked would otherwise ride along.
echo "Syncing $FORK..."
gh repo sync "$FORK" --branch master

base="$(gh api "repos/$FORK/git/ref/heads/master" --jq .object.sha)"
base_tree="$(gh api "repos/$FORK/git/commits/$base" --jq .tree.sha)"
manifest="repository=https://github.com/$REPO.git\\ncommit=$release_sha\\n"
tree="$(printf '{"base_tree":"%s","tree":[{"path":"%s","mode":"100644","type":"blob","content":"%s"}]}' \
	"$base_tree" "$MANIFEST" "$manifest" | gh api "repos/$FORK/git/trees" --input - --jq .sha)"
[ "$tree" != "$base_tree" ] || die "the hub already pins $release_sha"
hub_commit="$(printf '{"message":"better-monster-examine: update to %s","tree":"%s","parents":["%s"]}' \
	"$release_sha" "$tree" "$base" | gh api "repos/$FORK/git/commits" --input - --jq .sha)"
if gh api "repos/$FORK/git/ref/heads/$HUB_BRANCH" >/dev/null 2>&1; then
	gh api -X PATCH "repos/$FORK/git/refs/heads/$HUB_BRANCH" -f sha="$hub_commit" -F force=true >/dev/null
else
	gh api "repos/$FORK/git/refs" -f ref="refs/heads/$HUB_BRANCH" -f sha="$hub_commit" >/dev/null
fi

pr_body="$(mktemp)"
trap 'cleanup; rm -f "$notes" "$pr_body"' EXIT
{
	echo "Updates **$NAME** to [\`$release_sha\`](https://github.com/$REPO/commit/$release_sha)."
	echo
	echo "Released as **$release_name** — $release_url"
	echo
	echo "---"
	echo
	cat "$notes"
} >"$pr_body"

pr_title="better-monster-examine: update to $tag"
pr_number="$(gh pr list -R "$HUB" --author "${FORK%%/*}" --head "$HUB_BRANCH" --state open --json number --jq '.[0].number // empty')"
if [ -n "$pr_number" ]; then
	gh pr edit "$pr_number" -R "$HUB" --title "$pr_title" --body-file "$pr_body" >/dev/null
	echo "Hub PR updated: https://github.com/$HUB/pull/$pr_number"
else
	gh pr create -R "$HUB" --head "${FORK%%/*}:$HUB_BRANCH" --base master --title "$pr_title" --body-file "$pr_body"
fi
