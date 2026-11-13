#!/bin/sh -e
# tools/mutation.sh [CLASSES [TESTS]]   mutation-test code in :shared with PIT
#
# PIT makes small deliberate bugs ("mutants") in the compiled classes and
# runs the tests against each one. A mutant no test notices "survives":
# a bug of that shape would get through. CLASSES and TESTS are PIT globs,
# comma-separated; the defaults cover member editing (MemberEdit), where a
# quiet bug sends PluralKit a change nobody made.
#
# The report lands in shared/build/reports/pit (index.html, mutations.csv).
# Not run in CI: it's slow, and it's for code where the tests must be
# exceptionally sharp. Needs Java 17 or later and network access the first
# time, to fetch PIT; the jars are checked against the checksums below.
cd "$(git rev-parse --show-toplevel)"

classes=${1:-'me.pluralware.shared.model.MemberEdit,me.pluralware.shared.model.MemberDraft,me.pluralware.shared.model.MemberField'}
tests=${2:-'me.pluralware.shared.model.MemberEditTest'}

# The test classes are compiled for Java 17, which an older default java can't load.
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
# Java 8 and older say "1.8.0", the rest "17.0.2".
major=$("$java" -version 2>&1 | sed -n 's/.*version "\(1\.\)\{0,1\}\([0-9]*\).*/\2/p' | head -n 1)
if [ "${major:-0}" -lt 17 ]; then
  echo "tools/mutation.sh: needs Java 17 or later; $java is ${major:-unknown}. Set JAVA_HOME." >&2
  exit 1
fi

jars=${XDG_CACHE_HOME:-$HOME/.cache}/pluralware/pit
mkdir -p "$jars"
fetch() { # group artifact version sha256
  file=$jars/$2-$3.jar
  if [ ! -f "$file" ]; then
    curl -fsSL -o "$file.part" "https://repo1.maven.org/maven2/$(echo "$1" | tr . /)/$2/$3/$2-$3.jar"
    mv "$file.part" "$file"
  fi
  if ! echo "$4  $file" | sha256sum -c --status; then
    rm -f "$file"
    echo "tools/mutation.sh: $2-$3.jar didn't match its checksum; removed it" >&2
    exit 1
  fi
}
fetch org.pitest pitest 1.15.8 c4f8024a2faf52f3cf5d205b47b1e18fe3cdc30503aaa9ecf75fe67cf3b0ff76
fetch org.pitest pitest-entry 1.15.8 6cd71474c6bef7e05d7d53086253ded57c9f9a2e636ee99c39cfe96ec5131752
fetch org.pitest pitest-command-line 1.15.8 f1126c5b834b3c8c09f5d6c44a4e38929001283886f2328beaf6e12132d0af2e
fetch org.apache.commons commons-text 1.10.0 770cd903fa7b604d1f7ef7ba17f84108667294b2b478be8ed1af3bffb4ae0018
fetch org.apache.commons commons-lang3 3.12.0 d919d904486c037f8d193412da0c92e22a9fa24230b9d67a57855c5c31c7e94e

# Gradle knows the unit tests' classpath; an init script asks it, without
# adding anything to the build.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
init=$tmp/classpath.init.gradle.kts
cat > "$init" <<'EOF'
allprojects {
    if (path != ":shared") return@allprojects
    tasks.register("printUnitTestClasspath") {
        doLast {
            val type = Attribute.of("artifactType", String::class.java)
            println(configurations.getByName("debugUnitTestRuntimeClasspath").incoming
                .artifactView { attributes { attribute(type, "android-classes-jar") } }
                .files.joinToString(":"))
        }
    }
}
EOF
./gradlew -q :shared:compileDebugUnitTestKotlin
deps=$(./gradlew -q --no-configuration-cache --init-script "$init" :shared:printUnitTestClasspath)
built=$PWD/shared/build/tmp/kotlin-classes

report=$PWD/shared/build/reports/pit
rm -rf "$report"
"$java" -cp "$jars/*:$deps:$built/debug:$built/debugUnitTest" \
  org.pitest.mutationtest.commandline.MutationCoverageReport \
  --targetClasses "$classes" \
  --targetTests "$tests" \
  --sourceDirs "$PWD/shared/src/main/kotlin" \
  --mutators STRONGER \
  --threads 4 \
  --outputFormats HTML,CSV \
  --timestampedReports=false \
  --reportDir "$report" > "$report.log" 2>&1 || { tail -n 20 "$report.log" >&2; exit 1; }

grep -E 'Line Coverage|Generated [0-9]+ mutations' "$report.log"
survivors=$(grep -E ',(SURVIVED|NO_COVERAGE),' "$report/mutations.csv" | cut -d, -f2,4,5,6 | sed 's/^.*\.\([A-Za-z]*\),/\1,/')
if [ -n "$survivors" ]; then
  echo "Survivors (class, method, line, status):"
  echo "$survivors" | tr , ' ' | sed 's/^/  /'
fi
echo "Report: $report/index.html"
