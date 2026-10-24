#!/usr/bin/env bash
# Refuses release files that would publish enterprise data: APKs that bundle the private
# starting profile, and text that names enterprise hosts. Run on every file before uploading it
# to the public repository's Releases.
set -euo pipefail
enterprise='xin''dong'
status=0
for f in "$@"; do
  case "$f" in
    *.apk)
      listing="$(unzip -l "$f")"
      if grep -q 'assets/company-profile.json' <<<"$listing"; then
        echo "refused: $f bundles the private company profile (build with PUBLIC=1)" >&2; status=1
      fi
      if unzip -p "$f" 'assets/*' 2>/dev/null | grep -a -i -c -E "$enterprise" >/dev/null; then
        echo "refused: $f carries enterprise names in its assets" >&2; status=1
      fi ;;
    *.zip)
      listing="$(unzip -l "$f")"
      if grep -q -E 'PrivateConfig/|company-profile' <<<"$listing"; then
        echo "refused: $f contains PrivateConfig" >&2; status=1
      fi
      if unzip -p "$f" 2>/dev/null | grep -a -i -c -E "$enterprise" >/dev/null; then
        echo "refused: $f names enterprise hosts" >&2; status=1
      fi ;;
    *)
      if grep -a -i -q -E "$enterprise" "$f"; then
        echo "refused: $f names enterprise hosts" >&2; status=1
      fi ;;
  esac
done
[[ $status == 0 ]] && echo "public release check passed: $#"
exit $status
