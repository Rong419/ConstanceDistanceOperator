#!/bin/sh
 
for param in {1..2}
do 
    for sim in {1..3}
    do
    sed "s/FILE/${param}_${sim}/g" ./primates_${param}.xml > ./primates_${param}_${sim}.xml
    echo "use primates_${param}.xml to write primates_${param}_${sim}.xml"
    done
done
