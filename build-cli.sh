#!/bin/sh

set -e

lein run -m shadow.cljs.devtools.cli release cli

cd packages/shadow-cljs; npm install