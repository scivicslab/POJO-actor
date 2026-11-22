#!/bin/bash
# Runs the actor-guarantee tests (src/test/java/.../core/guarantees) against old versions of
# POJO-actor on W206, never on 5.12, at 1, 2 and 4 CPUs (-XX:ActiveProcessorCount).
#
#   tasks/guarantees-history/run.sh <test-commit> [repeats]
#
# <test-commit> holds the tests. Each version is taken from `git archive`, in its own directory of
# the pod, so no working tree is touched; the tests are laid over it. 656336c keeps its classes in
# another package and has no askNow, so shim-656336c/ is laid over it too.
# Environment: POD (default mvn-c96), VERSIONS ("label:rev ..."), CPUS ("1 2 4").
# Needs the pod: tasks/guarantees-history/w206-pod.sh setup; delete it when done.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
POD_SH="$HERE/w206-pod.sh"
TESTS_AT=${1:?usage: run.sh <test-commit> [repeats]}
REPEATS=${2:-3}
VERSIONS=${VERSIONS:-"656336c:656336c 63bb9a4:63bb9a4 be5f9a3:be5f9a3 main:$TESTS_AT"}
CPUS=${CPUS:-"1 2 4"}
TESTDIR=src/test/java/com/scivicslab/pojoactor/core/guarantees
POD=${POD:-mvn-c96}; export POD
TMP=$(mktemp -d); trap 'rm -rf $TMP' EXIT

"$POD_SH" check >/dev/null || { echo "precondition failed: the pod $POD is not ready (run w206-pod.sh setup)"; exit 2; }

for lv in $VERSIONS; do
  label=${lv%%:*}; rev=${lv#*:}
  dir=guar-$label
  "$POD_SH" push POJO-actor "$rev" "$dir"
  git -C "$REPO" archive --format=tar "$TESTS_AT" -- "$TESTDIR" > "$TMP/tests.tar"
  kubectl cp "$TMP/tests.tar" "$POD:/work/$dir-tests.tar"
  "$POD_SH" sh "tar -xf /work/$dir-tests.tar -C /work/$dir"
  if [ "$label" = 656336c ]; then
    "$POD_SH" sh "mkdir -p /work/$dir/src/test/java/com/scivicslab/pojoactor/core"
    for f in "$HERE"/shim-656336c/*.java; do
      kubectl cp "$f" "$POD:/work/$dir/src/test/java/com/scivicslab/pojoactor/core/$(basename "$f")"
    done
  fi
  for c in $CPUS; do
    for r in $(seq 1 "$REPEATS"); do
      ec=$("$POD_SH" mvn "$dir" "test -Dtest='com.scivicslab.pojoactor.core.guarantees.*Test' -DargLine=-XX:ActiveProcessorCount=$c -Dsurefire.failIfNoSpecifiedTests=false")
      "$POD_SH" sh "cp /work/$dir.log /work/$dir-cpu$c-run$r.log"
      echo "== $label cpus=$c run=$r $ec"
      "$POD_SH" sh "awk '/COMPILATION ERROR/ || /^\\[ERROR\\] \\/work/ || /^\\[ERROR\\]   / {print substr(\$0, 1, 330)} /^\\[(ERROR|INFO|WARNING)\\] Tests run:/ && !/ -- in / {last=\$0} END {print last}' /work/$dir.log"
    done
  done
done
echo HISTORY_DONE
