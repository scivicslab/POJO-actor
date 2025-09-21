#!/bin/bash

set -euo pipefail

export LANG=en_US.UTF-8
mvn javadoc:javadoc

dest=$PWD/docs

# Create destination directory if it doesn't exist
mkdir -p "${dest}"

# Remove existing docs if they exist (preserving dotfiles like .nojekyll)
rm -Rf "${dest}"/*

# Move generated javadoc to destination
mv target/site/apidocs/* "${dest}/"

# Ensure GitHub Pages does not run Jekyll on the generated site
touch "${dest}/.nojekyll"

