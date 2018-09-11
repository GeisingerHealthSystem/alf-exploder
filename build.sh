#!/bin/bash

HADOOP_VERSION=$(echo $@ | awk -F'=' '/-Dhadoop.version=[0-9].[0-9].[0-9]/ {print $2}')

if [[ $1 == "--help" ]]; then
	cat<<- EOF
	./build.sh [-Dhadoop.version=<version>] [passthrough-maven-opts]
	The hadoop.version can be any Hadoop version (CDH or Apache). It defaults to CDH 5.9.0.
	While a specific version is required for compiling, it should be able to process aggregated log files created by any version of Hadoop.

	For HDP, check docs.hortonworks.com -> release notes -> component versions
	See the hadoop version, such as "Apache Hadoop 2.7.3"

	EOF
fi

# Automatically fetch HDP version if an argument is not provided
if [[ -z ${HADOOP_VERSION} ]]; then
	cat<<-EOF
	Hadoop version not given, please see docs.hortonworks.com -> release notes -> component versions
	See the hadoop version, such as "Apache Hadoop 2.7.3"

	./build.sh -Dhadoop.version=2.7.3

	EOF
	exit
fi

# build code and copy dependencies
mvn clean dependency:copy-dependencies package $@
