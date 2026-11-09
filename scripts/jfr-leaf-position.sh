#!/bin/sh
# CPU time a call takes, by how many leaves a sum has and where the leaf is, read from JFR.
#
#     scripts/jfr-leaf-position.sh [checkout] [seconds]
#
# For each cell this starts a JVM with a flight recording of its own, runs souther.wasm.bench.
# LeafPositionBench in it, and prints the CPU milliseconds a call took: the execution samples JFR took
# (one every ten milliseconds under the profile settings) over how many calls were made. The clock
# is not read. The checkout needs `mvn test-compile` done and the class dependencies in
# target/classpath.txt (`mvn dependency:build-classpath -Dmdep.outputFile=target/classpath.txt`).
set -eu

checkout=${1:-.}
seconds=${2:-6}
java_home=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(which java)")")")}
recording=$(mktemp -d)/bench.jfr
classpath="$checkout/target/classes:$checkout/target/test-classes:$(cat "$checkout/target/classpath.txt")"

cell() {
    work=$1; size=$2; position=$3
    calls=$("$java_home/bin/java" -Xmx256m -Xss4m \
        -XX:StartFlightRecording=filename="$recording",settings=profile \
        -cp "$classpath" souther.wasm.bench.LeafPositionBench "$work" "$size" "$position" "$seconds" \
        2>/dev/null | awk '/^CALLS/ {print $2}')
    samples=$("$java_home/bin/jfr" summary "$recording" | awk '/jdk.ExecutionSample/ {print $2}')
    echo "$work,$size,$position,$calls,$samples,$(echo "scale=3; $samples * 10 / $calls" | bc)"
}

echo "work,leaves_or_fields,position,calls,samples,cpu_ms_per_call"
for leaves in 2 4 48 96; do
    for where in first middle last; do
        case $where in first) p=0;; middle) p=$((leaves / 2));; last) p=$((leaves - 1));; esac
        for work in read write build sort match; do
            cell "$work" "$leaves" "$p"
        done
    done
done
for fields in 4 16 48; do
    cell product "$fields" 0
done
