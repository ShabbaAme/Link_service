#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/classes
find src tests -name '*.java' -print > build/sources.txt
javac -encoding UTF-8 -Xlint:all -Werror -d build/classes @build/sources.txt
java -cp build/classes LinkServiceTest
