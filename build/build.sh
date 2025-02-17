#!/usr/bin/env bash

set -e

export POSTGRES_HOST=127.0.0.1
export POSTGRES_PORT=15432

export MYSQL_HOST=127.0.0.1
export MYSQL_PORT=13306

export SQL_SERVER_HOST=127.0.0.1
export SQL_SERVER_PORT=11433

export ORACLE_HOST=127.0.0.1
export ORACLE_PORT=11521

export CASSANDRA_HOST=127.0.0.1
export CASSANDRA_PORT=19042

export ORIENTDB_HOST=127.0.0.1
export ORIENTDB_PORT=12424

export GC_OPTS="-XX:ReservedCodeCacheSize=256m -XX:+TieredCompilation -XX:+UseG1GC -XX:+UseStringDeduplication"
export JVM_OPTS="-Dcommunity=false -Dquill.macro.log=false -Xms1024m -Xmx3g -Xss5m ${GC_OPTS}"

modules=$1

echo "Modules: $modules"

function show_mem() {
    free -m | awk 'NR==2{printf "Memory Usage: %s/%sMB (%.2f%%)\n", $3,$2,$3*100/$2 }'
    mem_details
    free_details
    docker_stats
}
export -f show_mem

function mem_details() {
    echo "===== System Memory Stats Start ====="
    ps -eo size,rss,%mem,cmd --sort=-size | head -20 | awk '{ hr=$1/1024; rr=$2/1024; printf("%13.2fMb ",hr); printf("%13.2fMb ",rr); print(" " $0) }'
    sleep 2
    echo "===== System Memory Stats End ====="
}
export -f mem_details

function free_details() {
    echo "===== Free Memory Stats Start ====="
    free -h
    sleep 2
    echo "===== Free Memory Stats End ====="
}
export -f free_details

function docker_stats() {
    echo "===== Docker Stats Start ====="
    docker stats --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" --no-stream
    docker ps
    sleep 4
    echo "===== Docker Stats End ====="
}
export -f docker_stats

export SBT_ARGS="-Dcommunity=false" #++$TRAVIS_SCALA_VERSION Only building for Scala 3 for now

# Enable coverage of builds when it is possible
# if [[ $TRAVIS_SCALA_VERSION == 3* ]]; then
#     export SBT_ARGS="$SBT_ARGS coverage"
# fi

function wait_for_databases() {
    show_mem

    # Scala 3 formatting not supported yet
    #sbt clean scalariformFormat test:scalariformFormat
    #sbt checkUnformattedFiles

    # Start sbt compilation and database setup in parallel
    sbt -Dmodules=base -Doracle=true $SBT_ARGS test & COMPILE=$!
    ./build/setup_databases.sh & SETUP=$!

    # Wait on database setup. If it has failed then kill compilation process and exit with error
    wait $SETUP

    if [[ "$?" != "0" ]]; then
       echo "Database setup failed"
       sleep 10
       kill -9 $COMPILE
       exit 1
    fi

    echo "Database Setup is finished, waiting for the compilation of core module"

    wait $COMPILE
    if [[ "$?" != "0" ]]; then
       echo "Compilation of core module has failed"
       sleep 10
       exit 1
    fi

    echo "Database Setup is finished"
    show_mem
}

function wait_for_mysql_postgres() {
    show_mem

    #sbt clean scalariformFormat test:scalariformFormat
    #sbt checkUnformattedFiles

    # Start sbt compilation and database setup in parallel
    sbt -Dmodules=base $SBT_ARGS test & COMPILE=$!
    ./build/setup_mysql_postgres_databases.sh & SETUP=$!

    # Wait on database setup. If it has failed then kill compilation process and exit with error
    wait $SETUP

    if [[ "$?" != "0" ]]; then
       echo "Database setup failed"
       sleep 10
       kill -9 $COMPILE
       exit 1
    fi
    echo "Database Setup is finished, waiting for the compilation of core module"

    wait $COMPILE
    if [[ "$?" != "0" ]]; then
       echo "Compilation of core module has failed"
       sleep 10
       exit 1
    fi

    echo "Database Setup is finished"
    show_mem
}

# function wait_for_bigdata() {
#     show_mem

#     sbt clean scalariformFormat test:scalariformFormat
#     sbt checkUnformattedFiles
#     sbt clean $SBT_ARGS quill-coreJVM/test:compile & COMPILE=$!
#     ./build/setup_bigdata.sh & SETUP=$!

#     wait $SETUP
#     if [[ "$?" != "0" ]]; then
#        echo "BigData Database setup failed"
#        sleep 10
#        kill -9 $COMPILE
#        exit 1
#     fi

#     wait $COMPILE
#     if [[ "$?" != "0" ]]; then
#        echo "Compilation of core module has failed"
#        sleep 10
#        exit 1
#     fi

#     echo "BigData Database Setup is finished"
#     show_mem
# }

function base_build() {
    sbt -Dmodules=base $SBT_ARGS test
}

function sqltest_build() {
    # Can use more memory here since not loading any images
    export JVM_OPTS="-Dcommunity=false -Dquill.macro.log=false -Xms2g -Xmx5g -Xss5m ${GC_OPTS}"
    sbt -Dmodules=sqltest -Doracle=true $SBT_ARGS test
}

function db_build() {
    echo "=== Waiting for Databases ==="
    wait_for_databases
    echo "=== Building Tests ==="
    ./build/aware_run.sh "sbt -Dmodules=db $SBT_ARGS test"
}

# function js_build() {
#     show_mem
#     export JVM_OPTS="-Dquill.macro.log=false -Xms1024m -Xmx4g -Xss5m ${GC_OPTS}"
#     sbt -Dmodules=js $SBT_ARGS test
# }

function async_build() {
    wait_for_mysql_postgres
    sbt -Dmodules=async $SBT_ARGS test
}

# function bigdata_build() {
#     wait_for_bigdata
#     sbt -Dmodules=bigdata $SBT_ARGS test
# }

function full_build() {
    wait_for_databases
    # wait_for_bigdata
    sbt $SBT_ARGS test tut
}

if [[ (! -z "$DOCKER_USERNAME") && (! -z "$DOCKER_USERNAME") ]]; then
  echo "Logging into Docker via $DOCKER_USERNAME for $TRAVIS_EVENT_TYPE"
  echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin

  echo "Getting Per-Account Statistics for Docker Pull Limits"
  TOKEN=$(curl --user "$DOCKER_USERNAME:$DOCKER_PASSWORD" "https://auth.docker.io/token?service=registry.docker.io&scope=repository:ratelimitpreview/test:pull" | jq -r .token)
  curl --head -H "Authorization: Bearer $TOKEN" https://registry-1.docker.io/v2/ratelimitpreview/test/manifests/latest
else
  echo "Getting Anonymous-Account Statistics for Docker Pull Limits"
  TOKEN=$(curl "https://auth.docker.io/token?service=registry.docker.io&scope=repository:ratelimitpreview/test:pull" | jq -r .token)
  curl --head -H "Authorization: Bearer $TOKEN" https://registry-1.docker.io/v2/ratelimitpreview/test/manifests/latest

  echo "Not Logging into Docker for $TRAVIS_EVENT_TYPE build, restarting Docker using GCR mirror"
fi

if [[ $modules == "base" ]]; then
    echo "Build Script: Doing Base Build"
    base_build
elif [[ $modules == "db" ]]; then
    echo "Build Script: Doing Database Build"
    db_build
elif [[ $modules == "sqltest" ]]; then
    echo "Build Script: Doing SQL Test Build"
    sqltest_build
# elif [[ $modules == "js" ]]; then
#     echo "Build Script: Doing JavaScript Build"
#     js_build
elif [[ $modules == "async" ]]; then
    echo "Build Script: Doing Async Database Build"
    async_build
# elif [[ $modules == "bigdata" ]]; then
#     echo "Build Script: Doing BigData Build"
#     bigdata_build
else
    echo "Build Script: Doing Full Build"
    full_build
fi

show_mem
echo "Tests completed. Shutting down"
time docker-compose down
# for 2.12 publish coverage
if [[ $TRAVIS_SCALA_VERSION == 2.12* ]]; then
    echo "Coverage"
    time sbt $SBT_ARGS coverageReport coverageAggregate
    pip install --user codecov && codecov
fi
show_mem

sleep 10
