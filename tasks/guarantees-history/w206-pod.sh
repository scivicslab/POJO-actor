#!/bin/bash
# chat-96 build helper on W206 (never on 5.12). Sources are git archives of commits.
#   w206.sh setup                          create the pod if needed; check every precondition
#   w206.sh check                          the preconditions only (exit 0 = all hold)
#   w206.sh push <repo> <rev> <dir>        archive <rev> of ~/works/<repo> into /work/<dir>
#   w206.sh mvn <dir> <mvn args...>        rm -rf target, then mvn; log in /work/<dir>.log
#   w206.sh sh <command>                   run a command in the pod
set -u
POD=${POD:-mvn-c96}
GV=25.0.0; MV=3.9.11
T=${TMPDIR:-/tmp}/w206-pod-tar
mkdir -p $T
check() {
  ok=0
  kubectl get pod $POD -o jsonpath='{.status.phase}' 2>/dev/null | awk '{print "pod phase: " $0; exit ($0=="Running"?0:1)}' || ok=1
  kubectl exec $POD -- bash -c 'java -version 2>&1 | head -2; mvn -v 2>&1 | head -1; echo "cpu.max: $(cat /sys/fs/cgroup/cpu.max)"; test -x /usr/local/bin/mvn && test -x /usr/local/bin/java && java -version 2>&1 | awk "/GraalVM CE 25/ {f=1} END {exit !f}"' 2>/dev/null || ok=1
  return $ok
}
case "$1" in
check) check ;;
setup)
  if ! kubectl get pod $POD >/dev/null 2>&1; then
    kubectl run $POD --image=ubuntu:24.04 --restart=Never \
      --overrides='{"spec":{"activeDeadlineSeconds":21600,"containers":[{"name":"'$POD'","image":"ubuntu:24.04","command":["sleep","21600"],"resources":{"requests":{"cpu":"1","memory":"2Gi"},"limits":{"cpu":"4","memory":"6Gi"}}}]}}' >/dev/null
    kubectl wait --for=condition=Ready pod/$POD --timeout=180s
  fi
  if ! check >/dev/null 2>&1; then
    kubectl exec $POD -- bash -c "set -e
      apt-get update -qq && apt-get install -y -qq curl ca-certificates git >/dev/null
      mkdir -p /opt/graalvm /opt/maven /work
      curl -fsSL https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-$GV/graalvm-community-jdk-${GV}_linux-x64_bin.tar.gz | tar -xz -C /opt/graalvm --strip-components=1
      curl -fsSL https://archive.apache.org/dist/maven/maven-3/$MV/binaries/apache-maven-$MV-bin.tar.gz | tar -xz -C /opt/maven --strip-components=1
      ln -sf /opt/graalvm/bin/java /usr/local/bin/java; ln -sf /opt/maven/bin/mvn /usr/local/bin/mvn"
  fi
  check   # the achievement check: the same commands, run again
  ;;
push)
  repo=$2; rev=$3; dir=$4
  git -C ~/works/$repo archive --format=tar --prefix=$dir/ $rev > $T/$dir.tar
  kubectl exec $POD -- rm -rf /work/$dir /work/$dir.tar
  kubectl cp $T/$dir.tar $POD:/work/$dir.tar
  kubectl exec $POD -- tar -xf /work/$dir.tar -C /work
  echo "pushed $repo $(git -C ~/works/$repo rev-parse --short $rev) -> /work/$dir"
  ;;
mvn)
  dir=$2; shift 2
  kubectl exec $POD -- bash -c "cd /work/$dir && rm -rf target && mvn -B $* > /work/$dir.log 2>&1; echo MVN_EXIT=\$?"
  ;;
sh)
  shift; kubectl exec $POD -- bash -c "$*"
  ;;
esac
