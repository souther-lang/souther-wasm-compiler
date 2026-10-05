#!/usr/bin/env bash
# The commit of Souther this build compiled against, read off the artifacts Maven resolved.
#
#     scripts/souther-revision.sh
#
# A SNAPSHOT version names whichever build of Souther was last published under it, so two things
# both said to be 0.3.1-SNAPSHOT may be two Southers. Each artifact Souther publishes records the
# commit it was built from as Implementation-Revision; this prints that commit, and fails unless
# every org.souther-lang artifact on the build's classpath records one and the same, since a build
# against two of them was built against no one Souther. Run it after the build, which resolved
# them, so that it reads what the build read.
set -euo pipefail
listed=$(mktemp)
trap 'rm -f "$listed"' EXIT
mvn -B -q dependency:list -DincludeGroupIds=org.souther-lang \
  -DoutputAbsoluteArtifactFilename=true -DoutputFile="$listed"

# groupId:artifactId:type:version:scope:path, and then a module name after " -- " where there is one.
jars=$(sed -n 's/^ *org\.souther-lang:[^:]*:jar:[^:]*:[^:]*:\([^ ]*\).*/\1/p' "$listed")
if [ -z "$jars" ]; then
  echo "the build resolved no org.souther-lang artifact" >&2
  exit 1
fi

revisions=$(
  for jar in $jars; do
    revision=$(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Implementation-Revision: //p')
    if [[ ! "$revision" =~ ^[0-9a-f]{40}$ ]]; then
      echo "$jar records no commit as Implementation-Revision" >&2
      exit 1
    fi
    echo "$revision $jar"
  done
)
if [ "$(cut -d' ' -f1 <<<"$revisions" | sort -u | wc -l)" -ne 1 ]; then
  echo "the build resolved Souther artifacts of more than one commit:" >&2
  echo "$revisions" >&2
  exit 1
fi
cut -d' ' -f1 <<<"$revisions" | head -1
