#!/bin/bash

export LANG=en_US.UTF-8
mvn javadoc:javadoc

dest=$PWD/doc

# Create destination directory if it doesn't exist
mkdir -p ${dest}

# Remove existing docs if they exist
rm -Rf ${dest}/POJO-actor

# Move generated javadoc to destination
mv target/site/apidocs ${dest}/POJO-actor

