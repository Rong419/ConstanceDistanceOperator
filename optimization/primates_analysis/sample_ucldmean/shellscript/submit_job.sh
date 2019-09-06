#!/bin/sh
TEMPLATE=template.sh

 
for param in {1..2}
do 
   for sim in {1..3}
    do
    sed 's/PARAM/${param}/g; s/SIM/${sim}/g' ${TEMPLATE} > ./temp.sl 
    echo "submit primates_${param}_${sim}.xml"
    sbatch temp.sl 
    rm -f temp.sl 
    sleep 5
    done
done

